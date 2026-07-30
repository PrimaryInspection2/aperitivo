package dev.amishko.aperitivo;

import dev.amishko.aperitivo.config.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class AperitivoApplicationTests extends BaseIT {

    static final ApplicationModules modules = ApplicationModules.of(AperitivoApplication.class);

    @Test
    void contextLoads() {
    }

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }

}
