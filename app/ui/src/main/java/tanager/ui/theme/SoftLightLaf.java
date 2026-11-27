package tanager.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;
import java.util.Properties;

/** 소프트 라이트 테마. FlatLightLaf 위에 themes/soft-light.properties 를 덮는다(ADR-0010, 실행 중 전환은 #185). */
public final class SoftLightLaf extends FlatLightLaf {
    @Override
    public String getName() {
        return "Tanager Soft Light";
    }

    @Override
    protected Properties getAdditionalDefaults() {
        return SoftTheme.LIGHT.properties();
    }
}
