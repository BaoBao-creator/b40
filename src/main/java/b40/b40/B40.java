package b40.b40;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class B40 implements ModInitializer {
    public static final String MOD_ID = "b40";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        B40Server.init();
    }
}
