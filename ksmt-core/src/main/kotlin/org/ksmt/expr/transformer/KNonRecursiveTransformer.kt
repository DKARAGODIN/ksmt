package org.ksmt.expr.transformer

import org.ksmt.KContext
import org.ksmt.expr.KApp
import org.ksmt.expr.KArrayLambda
import org.ksmt.expr.KExistentialQuantifier
import org.ksmt.expr.KExpr
import org.ksmt.expr.KUniversalQuantifier
import org.ksmt.sort.KArraySort
import org.ksmt.sort.KBoolSort
import org.ksmt.sort.KSort

abstract class KNonRecursiveTransformer(override val ctx: KContext) : KTransformer {
    private val transformed = hashMapOf<KExpr<*>, KExpr<*>>()
    private val exprStack = arrayListOf<KExpr<*>>()
    private var exprWasTransformed = false

    fun <T : KSort> apply(rootExpr: KExpr<T>): KExpr<T> {
        exprStack.add(rootExpr)
        while (exprStack.isNotEmpty()) {
            val expr = exprStack.removeLast()
            exprWasTransformed = true
            val transformedExpr = expr.accept(this)
            if (exprWasTransformed) {
                transformed[expr] = transformedExpr
            }
        }
        return transformedExpr(rootExpr) ?: error("expr was not properly transformed: $rootExpr")
    }

    fun <T : KSort> transformedExpr(expr: KExpr<T>): KExpr<T>? {
        @Suppress("UNCHECKED_CAST")
        return transformed[expr] as? KExpr<T>
    }

    fun KExpr<*>.transformAfter(dependencies: List<KExpr<*>>) {
        exprStack += this
        exprStack += dependencies
    }

    fun markExpressionAsNotTransformed() {
        exprWasTransformed = false
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : KSort> transformApp(expr: KApp<T, *>): KExpr<T> =
        transformAppAfterArgsTransformed(expr as KApp<T, KExpr<KSort>>) { transformedArgs ->
            if (transformedArgs == expr.args) return transformExpr(expr)
            val transformedApp = with(ctx) {
                mkApp(expr.decl, transformedArgs)
            }
            return transformExpr(transformedApp)
        }

    override fun <D : KSort, R : KSort> transform(expr: KArrayLambda<D, R>): KExpr<KArraySort<D, R>> =
        transformExprAfterTransformed(expr, listOf(expr.body)) { transformedBody ->
            with(ctx) {
                val body = transformedBody.single()
                if (body == expr.body) return transformExpr(expr)
                return transformExpr(mkArrayLambda(expr.indexVarDecl, body))
            }
        }

    override fun transform(expr: KExistentialQuantifier): KExpr<KBoolSort> =
        transformExprAfterTransformed(expr, listOf(expr.body)) { transformedBody ->
            with(ctx) {
                val body = transformedBody.single()
                if (body == expr.body) return transformExpr(expr)
                return transformExpr(mkExistentialQuantifier(body, expr.bounds))
            }
        }

    override fun transform(expr: KUniversalQuantifier): KExpr<KBoolSort> =
        transformExprAfterTransformed(expr, listOf(expr.body)) { transformedBody ->
            with(ctx) {
                val body = transformedBody.single()
                if (body == expr.body) return transformExpr(expr)
                return transformExpr(mkUniversalQuantifier(body, expr.bounds))
            }
        }

    inline fun <T : KSort, A : KSort> transformAppAfterArgsTransformed(
        expr: KApp<T, KExpr<A>>,
        transformer: (List<KExpr<A>>) -> KExpr<T>
    ): KExpr<T> = transformExprAfterTransformed(expr, expr.args, transformer)

    inline fun <T : KSort, A : KSort> transformExprAfterTransformed(
        expr: KExpr<T>,
        dependencies: List<KExpr<A>>,
        transformer: (List<KExpr<A>>) -> KExpr<T>
    ): KExpr<T> {
        val transformedDependencies = mutableListOf<KExpr<A>>()
        val notTransformedDependencies = mutableListOf<KExpr<A>>()
        for (dependency in dependencies) {
            val transformedDependency = transformedExpr(dependency)
            if (transformedDependency != null) {
                transformedDependencies += transformedDependency
                continue
            } else {
                notTransformedDependencies += dependency
            }
        }
        if (notTransformedDependencies.isNotEmpty()) {
            expr.transformAfter(notTransformedDependencies)
            markExpressionAsNotTransformed()
            return expr
        }
        return transformer(transformedDependencies)
    }
}