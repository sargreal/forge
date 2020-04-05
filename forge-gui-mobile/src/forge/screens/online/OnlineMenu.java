package forge.screens.online;

import forge.Forge;
import forge.assets.FImage;
import forge.assets.FSkinImage;
import forge.menu.FMenuItem;
import forge.menu.FPopupMenu;
import forge.model.FModel;
import forge.net.server.FServerManager;
import forge.properties.ForgePreferences;
import forge.properties.ForgePreferences.FPref;
import forge.screens.FScreen;
import forge.toolbox.FEvent;
import forge.toolbox.FEvent.FEventHandler;
import forge.toolbox.FOptionPane;
import forge.util.Callback;

public class OnlineMenu extends FPopupMenu {
    public enum OnlineScreen {
        Lobby("Lobby", FSkinImage.QUEST_GEAR, OnlineLobbyScreen.class),
        Chat("Chat", FSkinImage.QUEST_NOTES, OnlineChatScreen.class),
        Disconnect("Disconnect", FSkinImage.EXILE, null);;
 
        private final FMenuItem item;
        private final Class<? extends FScreen> screenClass;
        private FScreen screen;

        OnlineScreen(final String caption0, final FImage icon0, final Class<? extends FScreen> screenClass0) {
            screenClass = screenClass0;
            item = new FMenuItem(caption0, icon0, new FEventHandler() {
                @Override
                public void handleEvent(FEvent e) {
                    if(screenClass == null) {
                        FOptionPane.showConfirmDialog(
                                "Leave lobby? Doing so will shut down all connections and stop hosting.",
                                "Disconnect", new Callback<Boolean>() {
                                    @Override
                                    public void run(Boolean result) {
                                        if (result) {
                                            if (FServerManager.getInstance() != null)
                                                if(FServerManager.getInstance().isHosting()) {
                                                    FServerManager.getInstance().unsetReady();
                                                    FServerManager.getInstance().stopServer();
                                                }

                                            if (OnlineLobbyScreen.getfGameClient() != null)
                                                OnlineLobbyScreen.closeClient();

                                            Forge.back();
                                            screen = null;
                                            OnlineLobbyScreen.clearGameLobby();
                                        }
                                    }
                                });
                        return;
                    }
                    Forge.back(); //remove current screen from chain
                    open();
                    setPreferredScreen(OnlineScreen.this);
                }
            });
        }
        
        private void initializeScreen() {
            if (screen == null) { //don't initialize screen until it's opened the first time
                try {
                    screen = screenClass.newInstance();
                    screen.setHeaderCaption("Play Online - " + item.getText());
                }
                catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
            }
        }

        public void open() {
            initializeScreen();
            Forge.openScreen(screen);
        }

        public void setAsBackScreen(boolean replace) {
            initializeScreen();
            Forge.setBackScreen(screen, replace);
        }

        public FScreen getScreen() {
            initializeScreen();
            return screen;
        }
    }

    private static final ForgePreferences prefs = FModel.getPreferences();
    private static final OnlineMenu menu = new OnlineMenu();
    private static OnlineScreen preferredScreen;

    static {
        try {
            preferredScreen = OnlineScreen.valueOf(prefs.getPref(FPref.PLAY_ONLINE_SCREEN));
        }
        catch (Exception ex) {
            ex.printStackTrace();
            preferredScreen = OnlineScreen.Lobby;
            prefs.setPref(FPref.PLAY_ONLINE_SCREEN, preferredScreen.name());
            prefs.save();
        }
    }

    public static OnlineScreen getPreferredScreen() {
        return preferredScreen;
    }
    public static void setPreferredScreen(OnlineScreen preferredScreen0) {
        if (preferredScreen == preferredScreen0) { return; }
        preferredScreen = preferredScreen0;
        prefs.setPref(FPref.PLAY_ONLINE_SCREEN, preferredScreen.name());
        prefs.save();
    }

    public static OnlineMenu getMenu() {
        return menu;
    }

    private OnlineMenu() {
    }

    @Override
    protected void buildMenu() {
        FScreen currentScreen = Forge.getCurrentScreen();
        for (OnlineScreen ngs : OnlineScreen.values()) {
            addItem(ngs.item);
            ngs.item.setSelected(currentScreen == ngs.screen);
        }
    }
}
