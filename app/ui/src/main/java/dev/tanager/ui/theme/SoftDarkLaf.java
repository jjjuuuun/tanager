package dev.tanager.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import java.util.Properties;

/** 소프트 다크 테마. FlatDarkLaf 위에 themes/soft-dark.properties 를 덮는다(ADR-0010). */
public final class SoftDarkLaf extends FlatDarkLaf {
    @Override
    public String getName() {
        return "Tanager Soft Dark";
    }

    @Override
    protected Properties getAdditionalDefaults() {
        return SoftTheme.DARK.properties();
    }
}
