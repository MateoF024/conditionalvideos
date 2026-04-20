package org.mateof24.conditionalvideos.video;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class LegacyFormatParser {
    private LegacyFormatParser() {
    }

    public static Component parse(String text) {
        MutableComponent result = Component.empty();
        if (text == null || text.isEmpty()) {
            return result;
        }

        Style currentStyle = Style.EMPTY;
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '&' && i + 1 < text.length()) {
                ChatFormatting format = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (format != null) {
                    if (chunk.length() > 0) {
                        result.append(Component.literal(chunk.toString()).withStyle(currentStyle));
                        chunk.setLength(0);
                    }
                    currentStyle = applyFormat(currentStyle, format);
                    i++;
                    continue;
                }
            }
            chunk.append(current);
        }

        if (chunk.length() > 0) {
            result.append(Component.literal(chunk.toString()).withStyle(currentStyle));
        }
        return result;
    }

    private static Style applyFormat(Style style, ChatFormatting formatting) {
        if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY;
        }

        if (formatting.isColor()) {
            return style.withColor(formatting);
        }

        return switch (formatting) {
            case BOLD -> style.withBold(true);
            case ITALIC -> style.withItalic(true);
            case UNDERLINE -> style.withUnderlined(true);
            case STRIKETHROUGH -> style.withStrikethrough(true);
            case OBFUSCATED -> style.withObfuscated(true);
            default -> style;
        };
    }
}