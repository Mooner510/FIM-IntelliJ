package fish.crafting.fimplugin.plugin.minimessage

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.impl.JsonRecursiveElementVisitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.ColorHexUtil
import com.intellij.util.concurrency.AppExecutorUtil
import fish.crafting.fimplugin.plugin.util.javakotlin.isJava
import fish.crafting.fimplugin.plugin.util.javakotlin.isJson
import fish.crafting.fimplugin.plugin.util.javakotlin.isKotlin
import fish.crafting.fimplugin.plugin.util.javakotlin.isYaml
import org.jetbrains.kotlin.psi.*
import org.jetbrains.uast.*
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YamlRecursivePsiElementVisitor
import java.awt.Color

fun PsiElement.checkMCFormatBGT(controller: MiniMessageInlayController): Boolean {
    if(DumbService.isDumb(project)) return false
    if(quickShouldFormatMCText(controller)) return true
    return shouldFormatMCText()
}

fun PsiElement.checkMCFormatAndRun(controller: MiniMessageInlayController, run: (Boolean) -> Unit) {
    if(DumbService.isDumb(project)) {
        run.invoke(false)
        return
    }

    if(quickShouldFormatMCText(controller)) {
        run.invoke(true)
        return
    }

    ReadAction.nonBlocking<Boolean> { shouldFormatMCText() }
        .finishOnUiThread(ApplicationManager.getApplication().defaultModalityState, run)
        .submit(AppExecutorUtil.getAppExecutorService())
}

fun PsiElement.shouldOnlyRenderIfValid(): Boolean{
    return this is JsonStringLiteral || this is YAMLQuotedText
}

fun PsiElement.getLiteralValue(): Any? {
    return when(this) {
        is PsiLiteralExpression -> this.value
        is KtStringTemplateExpression -> this.entries.joinToString("") { it.text }
        is JsonStringLiteral -> this.value
        is YAMLQuotedText -> this.textValue
        else -> ""
    }
}

fun PsiElement.getParentLiteral(): PsiElement? {
    val clazz = if(this.language.isJava) PsiLiteralExpression::class.java
    else if(this.language.isKotlin) KtStringTemplateExpression::class.java
    else if(this.language.isJson) JsonStringLiteral::class.java
    else if(this.language.isYaml) YAMLQuotedText::class.java
    else return null

    return PsiTreeUtil.getParentOfType(this, clazz, false)
}

fun PsiElement.visitExpressions(run: (PsiElement) -> Unit) {
    if(language.isJava) {
        accept(object : JavaRecursiveElementVisitor() {
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                run.invoke(expression)
            }
        })
    }else if(language.isKotlin){
        accept(object : KtTreeVisitorVoid() {
            override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                run.invoke(expression)
            }
        })
    }else if(language.isJson) {
        accept(object : JsonRecursiveElementVisitor() {
            override fun visitStringLiteral(o: JsonStringLiteral) {
                run.invoke(o)
            }
        })
    }else if(language.isYaml){
        accept(object : YamlRecursivePsiElementVisitor() {
            override fun visitQuotedText(quotedText: YAMLQuotedText) {
                run.invoke(quotedText)
            }
        })
    }
}

private fun PsiElement.getRuntimeContainingClass(): PsiClass? {
    var callExpression: UCallExpression? = null
    if(this.language.isJava){
        val exprList = parent as? PsiExpressionList ?: return null
        callExpression = (exprList.parent as? PsiMethodCallExpression)?.toUElementOfType()
    }else if(this.language.isKotlin){
        val valArg = parent as? KtValueArgument ?: return null
        val exprList = valArg.parent as? KtValueArgumentList ?: return null
        callExpression = (exprList.parent as? KtCallExpression)?.toUElementOfType()
    }

    if(callExpression == null) return null
    return callExpression.resolve()?.containingClass
}

private fun checkUAnnotation(annotated: UAnnotated): Boolean {
    return annotated.uAnnotations.any {
        val langValue = it.findAttributeValue("value")?.evaluate() as? String
        it.qualifiedName == "org.intellij.lang.annotations.Language" && (langValue == "minimessage" || langValue == "minecraft")
    }
}

private fun PsiElement.hasLanguageAnnotation(): Boolean {
    val uExpr = toUElementOfType<UExpression>() ?: return false

    if (checkUAnnotation(uExpr)) return true

    val uCall = uExpr.getParentOfType<UCallExpression>()
    if (uCall != null) {
        val method = uCall.resolve()
        if (method != null) {
            val argIndex = uCall.valueArguments.indexOfFirst { arg -> arg.sourcePsi != null && PsiTreeUtil.isAncestor(arg.sourcePsi!!, this, false) }
            if (argIndex != -1 && argIndex < method.parameterList.parametersCount) {
                val param = method.parameterList.parameters[argIndex].toUElement()
                if (param is UParameter && checkUAnnotation(param)) return true
            }
        }
    }

    val uVar = uExpr.getParentOfType<UVariable>()
    if (uVar != null && uVar.uastInitializer?.sourcePsi != null && PsiTreeUtil.isAncestor(uVar.uastInitializer!!.sourcePsi!!, this, false)) {
        if (checkUAnnotation(uVar)) return true
    }

    val uMethod = uExpr.getParentOfType<UMethod>()
    if (uMethod != null) {
        if (uMethod.uastBody?.sourcePsi != null && PsiTreeUtil.isAncestor(uMethod.uastBody!!.sourcePsi!!, this, false)) {
            if (checkUAnnotation(uMethod)) return true
        }
    }

    return false
}


/**
 * This method checks whether the Literal Expression is being edited right now.
 * OR when no checks are necessary (formatting is always enabled (JSON, YAML))
 */
fun PsiElement.quickShouldFormatMCText(controller: MiniMessageInlayController): Boolean {
    if(this is JsonStringLiteral) {
        val next = this.nextSibling
        if(next != null && next.text == ":") return false
        return true
    }

    if(this is YAMLQuotedText) {
        return true
    }

    //We are editing this rn
    return controller.matchesCached(this)
}

fun PsiElement.shouldFormatMCText(): Boolean {
    if (hasLanguageAnnotation()) return true

    val uElement = toUElementOfType<UExpression>() ?: return false
    val uCall = uElement.getParentOfType<UCallExpression>() ?: return false
    val method = uCall.resolve() ?: return false

    if(TextFormatRegistryService.instance.isMethodValid(method)) return true

    val containingClass = method.containingClass
    containingClass ?: return false

    val declaredClassValid = TextFormatRegistryService.instance.isClassValid(containingClass)
    if(declaredClassValid) return true

    val runtimeContainingClass = getRuntimeContainingClass() ?: return false
    return TextFormatRegistryService.instance.isClassValid(runtimeContainingClass)
}

fun String.toHexOrNull(): Color? {
    try {
        val hex = ColorHexUtil.fromHexOrNull(this)
        return hex
    }catch (ignored: Exception) {}
    return null
}
