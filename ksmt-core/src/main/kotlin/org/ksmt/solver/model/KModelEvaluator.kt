package org.ksmt.solver.model

import org.ksmt.KContext
import org.ksmt.expr.KConst
import org.ksmt.expr.KExpr
import org.ksmt.expr.KFunctionApp
import org.ksmt.expr.transformer.KTransformer
import org.ksmt.expr.rewrite.KExprSubstitutor
import org.ksmt.solver.KModel
import org.ksmt.expr.KAndExpr
import org.ksmt.expr.KApp
import org.ksmt.expr.KBitVecValue
import org.ksmt.expr.KEqExpr
import org.ksmt.expr.KFalse
import org.ksmt.expr.KFpRoundingMode
import org.ksmt.expr.KIntNumExpr
import org.ksmt.expr.KIteExpr
import org.ksmt.expr.KNotExpr
import org.ksmt.expr.KOrExpr
import org.ksmt.expr.KRealNumExpr
import org.ksmt.expr.KTrue
import org.ksmt.sort.KArraySort
import org.ksmt.sort.KBvSort
import org.ksmt.sort.KBoolSort
import org.ksmt.sort.KFpRoundingModeSort
import org.ksmt.sort.KFpSort
import org.ksmt.sort.KIntSort
import org.ksmt.sort.KRealSort
import org.ksmt.sort.KSort
import org.ksmt.sort.KSortVisitor
import org.ksmt.sort.KUninterpretedSort

@Suppress("TooManyFunctions")
open class KModelEvaluator(
    override val ctx: KContext,
    private val model: KModel,
    private val isComplete: Boolean
) : KTransformer {
    val evaluatedExpressions: MutableMap<KExpr<*>, KExpr<*>> = hashMapOf()

    private val evaluatedFunctions: MutableMap<KExpr<*>, KExpr<*>> = hashMapOf()

    fun <T : KSort> KExpr<T>.eval(): KExpr<T> = accept(this@KModelEvaluator)

    override fun <T : KSort> transform(expr: KFunctionApp<T>): KExpr<T> = expr.evalFunction()
    override fun <T : KSort> transform(expr: KConst<T>): KExpr<T> = expr.evalFunction()

    override fun <T : KSort> transformExpr(expr: KExpr<T>): KExpr<T> = expr.evalExpr { expr }
    override fun <T : KSort> transformApp(expr: KApp<T, *>): KExpr<T> = expr.evalExpr {
        mkApp(expr.decl, expr.args.map { it.eval() })
    }

    @Suppress("DuplicatedCode")
    override fun transform(expr: KAndExpr): KExpr<KBoolSort> = expr.evalExpr {
        val evaluatedArgs = mutableListOf<KExpr<KBoolSort>>()

        for (arg in expr.args) {
            val evaluated = arg.eval()
            if (evaluated == trueExpr) continue
            if (evaluated == falseExpr) return@evalExpr falseExpr
            evaluatedArgs.add(evaluated)
        }

        if (evaluatedArgs.isEmpty()) return@evalExpr trueExpr

        mkAnd(evaluatedArgs)
    }

    @Suppress("DuplicatedCode")
    override fun transform(expr: KOrExpr): KExpr<KBoolSort> = expr.evalExpr {
        val evaluatedArgs = mutableListOf<KExpr<KBoolSort>>()

        for (arg in expr.args) {
            val evaluated = arg.eval()
            if (evaluated == falseExpr) continue
            if (evaluated == trueExpr) return@evalExpr trueExpr
            evaluatedArgs.add(evaluated)
        }

        if (evaluatedArgs.isEmpty()) return@evalExpr falseExpr

        mkOr(evaluatedArgs)
    }

    override fun transform(expr: KNotExpr): KExpr<KBoolSort> = expr.evalExpr {
        when (val evaluated = expr.arg.eval()) {
            trueExpr -> falseExpr
            falseExpr -> trueExpr
            else -> mkNot(evaluated)
        }
    }

    override fun <T : KSort> transform(expr: KEqExpr<T>): KExpr<KBoolSort> = expr.evalExpr {
        val lhs = expr.lhs.eval()
        val rhs = expr.rhs.eval()

        if (lhs == rhs) return@evalExpr trueExpr

        mkEq(lhs, rhs)
    }

    override fun <T : KSort> transform(expr: KIteExpr<T>): KExpr<T> = expr.evalExpr {
        when (val condition = expr.condition.eval()) {
            trueExpr -> expr.trueBranch.eval()
            falseExpr -> expr.falseBranch.eval()
            else -> mkIte(condition, expr.trueBranch.eval(), expr.falseBranch.eval())
        }
    }

    override fun transformIntNum(expr: KIntNumExpr): KExpr<KIntSort> = expr.evalExpr { expr }

    override fun <T : KBvSort> transformBitVecValue(expr: KBitVecValue<T>): KExpr<T> = expr.evalExpr { expr }

    override fun transform(expr: KRealNumExpr): KExpr<KRealSort> = expr.evalExpr { expr }
    override fun transform(expr: KTrue): KExpr<KBoolSort> = expr.evalExpr { expr }
    override fun transform(expr: KFalse): KExpr<KBoolSort> = expr.evalExpr { expr }


    @Suppress("UNCHECKED_CAST")
    inline fun <T : KSort, E : KExpr<T>> E.evalExpr(
        crossinline eval: KContext.() -> KExpr<T>
    ): KExpr<T> = evaluatedExpressions.getOrPut(this) { ctx.eval() } as KExpr<T>

    @Suppress("MemberVisibilityCanBePrivate")
    fun <T : KSort, A : KExpr<*>> KApp<T, A>.evalFunction(): KExpr<T> = evalExpr {
        evalFunction(args.map { it.eval() })
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : KSort, A : KExpr<*>> KApp<T, A>.evalFunction(
        args: List<KExpr<*>>
    ): KExpr<T> = evaluatedFunctions.getOrPut(this) {
        with(ctx) {
            val interpretation = model.interpretation(decl)

            if (interpretation == null && !isComplete) {
                return@getOrPut mkApp(decl, args)
            }

            if (interpretation == null) {
                return@getOrPut sort.sampleValue()
            }

            check(args.size == interpretation.vars.size) {
                "${interpretation.vars.size} arguments expected but ${args.size} provided"
            }

            evalFuncInterp(interpretation, args)
        }
    } as KExpr<T>


    @Suppress("UNCHECKED_CAST")
    open fun <T : KSort> evalFuncInterp(
        interpretation: KModel.KFuncInterp<T>,
        args: List<KExpr<*>>
    ): KExpr<T> = with(ctx) {
        val varSubstitution = KExprSubstitutor(ctx).apply {
            interpretation.vars.zip(args).forEach { (v, a) ->
                val app = mkApp(v, emptyList())
                substitute(app as KExpr<KSort>, a as KExpr<KSort>)
            }
        }

        val entries = interpretation.entries.map { entry ->
            KModel.KFuncInterpEntry(
                entry.args.map { varSubstitution.apply(it) },
                varSubstitution.apply(entry.value)
            )
        }

        // in case of partial interpretation we can generate any default expr to preserve expression correctness
        val defaultExpr = interpretation.default ?: interpretation.sort.sampleValue()
        val default = varSubstitution.apply(defaultExpr)

        return entries.foldRight(default) { entry, acc ->
            val argBinding = mkAnd(entry.args.zip(args) { ea, a -> mkEq(ea as KExpr<KSort>, a as KExpr<KSort>) })
            mkIte(argBinding, entry.value, acc)
        }
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T : KSort> T.sampleValue(): KExpr<T> = with(ctx) {
        accept(object : KSortVisitor<KExpr<T>> {
            override fun visit(sort: KBoolSort): KExpr<T> = trueExpr as KExpr<T>
            override fun visit(sort: KIntSort): KExpr<T> = 0.expr as KExpr<T>
            override fun visit(sort: KRealSort): KExpr<T> = mkRealNum(0) as KExpr<T>
            override fun <S : KBvSort> visit(sort: S): KExpr<T> = mkBv("0", sort.sizeBits) as KExpr<T>
            override fun <S : KFpSort> visit(sort: S): KExpr<T> = mkFp(0f, sort) as KExpr<T>
            override fun visit(sort: KFpRoundingModeSort): KExpr<T> =
                mkFpRoundingModeExpr(KFpRoundingMode.RoundTowardZero) as KExpr<T>

            override fun <D : KSort, R : KSort> visit(sort: KArraySort<D, R>): KExpr<T> =
                mkArrayConst(sort, sort.range.sampleValue()) as KExpr<T>

            override fun visit(sort: KUninterpretedSort): KExpr<T> =
                error("Uninterpreted sort has no values")
        })
    }
}
