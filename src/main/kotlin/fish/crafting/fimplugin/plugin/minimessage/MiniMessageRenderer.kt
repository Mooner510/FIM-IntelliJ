package fish.crafting.fimplugin.plugin.minimessage

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import fish.crafting.fimplugin.plugin.minimessage.parser.MiniMessageParser
import fish.crafting.fimplugin.plugin.minimessage.parser.TextComponent
import fish.crafting.fimplugin.plugin.minimessage.parser.TextStyling
import fish.crafting.fimplugin.plugin.minimessage.parser.resolver.GradientTagResolver
import fish.crafting.fimplugin.plugin.minimessage.parser.resolver.RainbowTagResolver
import fish.crafting.fimplugin.plugin.util.ObfuscationUtil
import java.awt.*

class MiniMessageRenderer(private val components: ArrayList<TextComponent>,
                          private var text: String,
                          private var width: Int = 1,
                          private var renderIndex: Int = 0,
                          private var attachedTimer: Boolean = false,
                          private var renderBG: Boolean = false): EditorCustomElementRenderer {

    constructor(text: String) : this(MiniMessageParser.parseOrLegacy(text), text)

    private var lastColor: Color? = null
    private var isObfuscated: Boolean? = null
    fun hasObfuscation(): Boolean {
        if(isObfuscated == null) {
            isObfuscated = false

            for (component in components) {
                if(component.styling.obfuscated) {
                    isObfuscated = true
                    break
                }
            }
        }

        return isObfuscated ?: false
    }

    val hasAttachedTimer get() = attachedTimer
    fun attachTimer() {
        attachedTimer = true
    }

    fun updateText(newText: String): Boolean {
        if(text == newText) return false

        components.clear()
        components.addAll(MiniMessageParser.parseOrLegacy(newText))
        isObfuscated = null //Re-calculate obfuscation
        return true
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return width
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        renderIndex++

        val g2 = g as Graphics2D
        val editor = inlay.editor

        var blank = true
        var width = 0
        var x = targetRegion.x
        val y = targetRegion.y + editor.ascent

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)

        var styledFont: Font? = null
        lastColor = null
        for (component in components) {
            val baseFont = if (component.styling.bold) MinecraftFont.bold else MinecraftFont.font
            styledFont = applyStylingToFont(editor, baseFont, component.styling)

            val addedWidth = when(val baseColor = component.styling.color) {
                is GradientTagResolver.GradientColorElement ->
                    drawComponent(g2, component, x, y, editor, { i -> baseColor.getColor(i, renderIndex) }, false)
                is RainbowTagResolver.RainbowColorElement ->
                    drawComponent(g2, component, x, y, editor, { i -> baseColor.getColor(i, renderIndex) }, false)
                is TextStyling.SolidColorElement -> {
                    val c = baseColor.color
                    lastColor = Color(c.red, c.green, c.blue, 50)
                    drawComponent(g2, component, x, y, editor, { _ -> c }, true)
                }
                else -> 0
            }

            if(blank) blank = component.content.isBlank()

            x += addedWidth
            width += addedWidth
        }

        val updateWidth = this.width != width

        val newRenderBG = width == 0 || blank
        val updateBG = renderBG != newRenderBG
        renderBG = newRenderBG

        if(renderBG){
            if(styledFont == null) styledFont = MinecraftFont.font.deriveFont(editor.colorsScheme.editorFontSize + 3f)
            val height = g2.getFontMetrics(styledFont).ascent
            if(width == 0) width = height

            if(!updateBG){ //Was supposed to render bg, and that didn't change
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

                val arc = height / 2
                g2.color = lastColor ?: Color(255, 0, 0, 50)
                g2.fillRoundRect(targetRegion.x, (y - height * 0.8).toInt(), this.width, height, arc, arc)
            }
        }

        this.width = width
        if(updateWidth || updateBG) inlay.update()
    }

    private fun drawComponent(g2: Graphics2D, component: TextComponent, x: Int, y: Int, editor: Editor, colorGetter: (Int) -> Color, isSolidColor: Boolean): Int {
        val text = component.content
        if (text.isEmpty()) return 0

        val styling = component.styling
        val baseFont = if (styling.bold) MinecraftFont.bold else MinecraftFont.font
        val styledBaseFont = applyStylingToFont(editor, baseFont, styling)
        val styledUnifont = applyStylingToFont(editor, MinecraftFont.unifont, styling)

        var currentX = x

        if (isSolidColor) {
            val color = colorGetter(0)
            val shadowColor = styling.getShadow(color)
            var startIndex = 0
            while (startIndex < text.length) {
                val canDisplay = baseFont.canDisplay(text[startIndex])
                var endIndex = startIndex + 1
                while (endIndex < text.length && baseFont.canDisplay(text[endIndex]) == canDisplay) {
                    endIndex++
                }
                val substring = text.substring(startIndex, endIndex)
                val font = if (canDisplay) styledBaseFont else styledUnifont
                val textToDraw = if (styling.obfuscated) ObfuscationUtil.obfuscate(g2, g2.getFontMetrics(font), font, substring) else substring

                g2.font = font
                g2.color = shadowColor
                g2.drawString(textToDraw, currentX + 2, y + 2)
                g2.color = color
                g2.drawString(textToDraw, currentX, y)

                currentX += g2.fontMetrics.stringWidth(textToDraw)
                startIndex = endIndex
            }
        } else {
            for ((index, char) in text.withIndex()) {
                val font = if (baseFont.canDisplay(char)) styledBaseFont else styledUnifont
                g2.font = font
                val charToDraw = if (styling.obfuscated) ObfuscationUtil.obfuscate(g2, g2.getFontMetrics(font), font, char.toString()) else char.toString()
                val color = colorGetter(index)

                g2.color = styling.getShadow(color)
                g2.drawString(charToDraw, currentX + 2, y + 2)
                g2.color = color
                g2.drawString(charToDraw, currentX, y)

                currentX += g2.fontMetrics.stringWidth(charToDraw)
            }
        }

        val totalWidth = currentX - x

        if (styling.underlined || styling.strikethrough) {
            g2.color = colorGetter(0)
            if (styling.underlined) {
                g2.drawLine(x, y + 1, x + totalWidth, y + 1)
            }
            if (styling.strikethrough) {
                val strikeY = y - g2.getFontMetrics(styledBaseFont).height / 3
                g2.drawLine(x, strikeY, x + totalWidth, strikeY)
            }
        }

        return totalWidth
    }

    private fun applyStylingToFont(editor: Editor, base: Font, style: TextStyling): Font {
        val fontSize: Int = editor.colorsScheme.editorFontSize
        var styleFlags = Font.PLAIN
        if (style.bold) styleFlags = styleFlags or Font.BOLD
        if (style.italic) styleFlags = styleFlags or Font.ITALIC
        return base.deriveFont(styleFlags, fontSize.toFloat() + 3f)
    }
}