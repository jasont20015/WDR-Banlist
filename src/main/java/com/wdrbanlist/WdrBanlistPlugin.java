package com.wdrbanlist;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import com.google.inject.Provides;
import joptsimple.internal.Strings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.SpriteID;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.PlayerMenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

import javax.inject.Inject;
import javax.inject.Named;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "WDR banlist",
		description = "We do raids banlist",
		tags = {"wdr", "banlist", "we", "do", "raids"},
		enabledByDefault = false
)
public class WdrBanlistPlugin extends Plugin {
	private static final String BANLIST = "Banlist";

	private static final Pattern TRADING_WITH_PATTERN = Pattern.compile("Trading [W|w]ith:(<br>|\\s)(.*)");

	private static final int PLAYER_TRADE_OFFER_GROUP_ID = 335;
	private static final int PLAYER_TRADE_OFFER_TRADING_WITH = 31;
	private static final int PLAYER_TRADE_OFFER_TRADE_MODIFIED_ME = 26;
	private static final int PLAYER_TRADE_OFFER_TRADE_MODIFIED_THEM = 29;

	private static final int PLAYER_TRADE_CONFIRMATION_GROUP_ID = 334;
	private static final int PLAYER_TRADE_CONFIRMATION_TRADING_WITH = 30;
	private static final int PLAYER_TRADE_CONFIRMATION_TRADE_MODIFIED_THEM = 31;

	private static final int COX_PARTY_LIST_GROUP_ID = 499;
	private static final int TOB_PARTY_LIST_GROUP_ID = 364;
	private static final int COX_PARTY_DETAILS_GROUP_ID = 507;
	private static final int TOB_PARTY_DETAILS_GROUP_ID = 50;

	// https://raw.githubusercontent.com/RuneStar/cache-names/master/names.tsv
	private static final int SCRIPT_ID_TOB_HUD_DRAW = 2297;
	private static final int SCRIPT_ID_RAIDS_SIDEPANEL_ENTRY_SETUP = 1550;

	private static final List<Integer> TRADE_SCREEN_GROUP_IDS = Arrays.asList(
			PLAYER_TRADE_OFFER_GROUP_ID,
			PLAYER_TRADE_CONFIRMATION_GROUP_ID
	);

	private static final List<Integer> MENU_WIDGET_IDS = ImmutableList.of(
			WidgetInfo.FRIENDS_LIST.getGroupId(),
			WidgetInfo.FRIENDS_CHAT.getGroupId(),
			WidgetInfo.CHATBOX.getGroupId(),
			WidgetInfo.RAIDING_PARTY.getGroupId(),
			WidgetInfo.PRIVATE_CHAT_MESSAGE.getGroupId(),
			WidgetInfo.IGNORE_LIST.getGroupId(),
			COX_PARTY_DETAILS_GROUP_ID,
			TOB_PARTY_DETAILS_GROUP_ID
	);

	private static final ImmutableList<String> AFTER_OPTIONS = ImmutableList.of(
			"Message", "Add ignore", "Remove friend", "Delete", "Kick", "Reject"
	);

	@Inject
	private Client client;

	@Inject
	private WdrBanlistConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private MenuManager menuManager;

	@Getter(AccessLevel.PACKAGE)
	private BufferedImage reportButton;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	BanlistManager banlistManager;

	@Inject
	ClientThread clientThread;

	@Inject
	@Named("developerMode")
	boolean developerMode;

	@Inject
	WdrBanlistInputListener hotkeyListener;

	@Inject
	KeyManager keyManager;

	@Getter(AccessLevel.PACKAGE)
	@Setter(AccessLevel.PACKAGE)
	private boolean hotKeyPressed;

	@Provides
	WdrBanlistConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(WdrBanlistConfig.class);
	}

	@Override
	protected void startUp() {
		if (config.playerOption() && client != null) {
			menuManager.addPlayerMenuItem(BANLIST);
		}

		keyManager.registerKeyListener(hotkeyListener);
		spriteManager.getSpriteAsync(SpriteID.CHATBOX_REPORT_BUTTON, 0, s -> reportButton = s);
		banlistManager.refresh(this::colorAll);
	}

	@Override
	protected void shutDown() {
		if (config.playerOption() && client != null) {
			menuManager.removePlayerMenuItem(BANLIST);
		}
		keyManager.unregisterKeyListener(hotkeyListener);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!event.getGroup().equals(WdrBanlistConfig.CONFIG_GROUP)) {
			return;
		}

		if (event.getKey().equals(WdrBanlistConfig.PLAYER_OPTION)) {
			if (!Boolean.parseBoolean(event.getOldValue()) && Boolean.parseBoolean(event.getNewValue())) {
				menuManager.addPlayerMenuItem(BANLIST);
			} else if (Boolean.parseBoolean(event.getOldValue()) && !Boolean.parseBoolean(event.getNewValue())) {
				menuManager.removePlayerMenuItem(BANLIST);
			}
		} else if (event.getKey().equals(WdrBanlistConfig.PLAYER_TEXT_COLOR)) {
			colorAll();
		}
	}

	@Subscribe
	public void onFocusChanged(FocusChanged focusChanged) {
		if (!focusChanged.isFocused()) {
			hotKeyPressed = false;
		}
	}

	private void colorAll() {
		clientThread.invokeLater(() -> {
			colorFriendsChat();

			colorRaidsSidePanel();
			colorRaidsPartyList();
			colorRaidsParty();

			colorTobHud();
			colorTobParty();
			colorTobPartyList();
		});
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event) {
		if (!config.menuOption() || (!hotKeyPressed && config.useHotkey())) {
			return;
		}

		int groupId = WidgetInfo.TO_GROUP(event.getActionParam1());
		String option = event.getOption();

		if (!MENU_WIDGET_IDS.contains(groupId) || !AFTER_OPTIONS.contains(option)) {
			return;
		}

		if (option.equals("Message") && groupId == WidgetInfo.FRIENDS_LIST.getGroupId()) {
			return;
		}

		final MenuEntry lookup = new MenuEntry();
		lookup.setOption(BANLIST);
		lookup.setTarget(event.getTarget());
		lookup.setType(MenuAction.RUNELITE.getId());
		lookup.setParam0(event.getActionParam0());
		lookup.setParam1(event.getActionParam1());
		lookup.setIdentifier(event.getIdentifier());

		MenuEntry[] newMenu = ObjectArrays.concat(client.getMenuEntries(), lookup);
		ArrayUtils.swap(newMenu, newMenu.length - 1, newMenu.length - 2);
		client.setMenuEntries(newMenu);
	}

	@Subscribe
	public void onPlayerMenuOptionClicked(PlayerMenuOptionClicked event) {
		if (event.getMenuOption().equals(BANLIST)) {
				alertPlayerWarning(event.getMenuTarget(), true, false);


		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		Runnable color = null;
		switch (event.getScriptId()) {
			case ScriptID.FRIENDS_CHAT_CHANNEL_REBUILD:
				color = this::colorFriendsChat;
				break;
			case SCRIPT_ID_TOB_HUD_DRAW:
				color = this::colorTobHud;
				break;
			case SCRIPT_ID_RAIDS_SIDEPANEL_ENTRY_SETUP:
				color = this::colorRaidsSidePanel;
				break;
		}

		if (color != null) {
			clientThread.invokeLater(color);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		// check trade screens on tick. When a player removes/adds items, the warning message gets recreated
		for (int gid : TRADE_SCREEN_GROUP_IDS) {
			Widget w = client.getWidget(gid, 0);
			if (w != null) {
				showTradeWarning(gid);
			}
		}
	}


	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event) {
		String rsn = Text.toJagexName(event.getMember().getName());
		String local = client.getLocalPlayer().getName();
		if (rsn.equals(local)) {
			return;
		}

		alertPlayerWarning(rsn, false, true);
	}


	@Subscribe
	public void onWidgetLoaded(final WidgetLoaded event) {
		int groupId = event.getGroupId();
		Runnable task = null;
		switch (groupId) {
			case COX_PARTY_LIST_GROUP_ID:
				task = this::colorRaidsPartyList;
				break;
			case COX_PARTY_DETAILS_GROUP_ID:
				task = this::colorRaidsParty;
				break;
			case TOB_PARTY_DETAILS_GROUP_ID:
				task = this::colorTobParty;
				break;
			case TOB_PARTY_LIST_GROUP_ID:
				task = this::colorTobPartyList;
				break;
		}

		if (task != null) {
			clientThread.invokeLater(task);
		}
	}

	@Schedule(period = 15, unit = ChronoUnit.MINUTES)
	public void refreshList() {
		banlistManager.refresh(this::colorAll);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted ce) {
		if (/*developerMode &&*/ ce.getCommand().equals("banlistd")) {
			banlistManager.refresh(() -> {
				if (ce.getArguments().length > 0) {

					// refresh is async, so wait a bit before adding the test rsn
					banlistManager.put(String.join(" ", ce.getArguments()));
				}
				colorAll();
			});
		} else if (ce.getCommand().equals("banlist")) {
			final String rsn = String.join(" ", ce.getArguments());
			alertPlayerWarning(rsn, true, false);
		}
	}

	private void showTradeWarning(int groupId) {
		int tradeModifiedId = PLAYER_TRADE_CONFIRMATION_TRADE_MODIFIED_THEM;
		int tradeModifiedMeId = PLAYER_TRADE_CONFIRMATION_TRADING_WITH;
		int tradingWithId = PLAYER_TRADE_CONFIRMATION_TRADING_WITH;
		if (groupId == PLAYER_TRADE_OFFER_GROUP_ID) {
			tradeModifiedId = PLAYER_TRADE_OFFER_TRADE_MODIFIED_THEM;
			tradingWithId = PLAYER_TRADE_OFFER_TRADING_WITH;
			tradeModifiedMeId = PLAYER_TRADE_OFFER_TRADE_MODIFIED_ME;
		}

		Widget tradeModified = client.getWidget(groupId, tradeModifiedId);
		Widget tradingWith = client.getWidget(groupId, tradingWithId);
		Widget tradeModifiedMe = client.getWidget(groupId, tradeModifiedMeId);
		if (tradingWith == null || tradeModified == null) {
			log.warn("no trading with widget found");
			return;
		}

		Matcher m = TRADING_WITH_PATTERN.matcher(tradingWith.getText());
		if (!m.matches()) {
			log.warn("no rsn found in trading with widget: " + tradingWith.getText());
			return;
		}
		String trader = m.group(2);
		Ban ban = banlistManager.get(trader);
		if (ban == null) {
			return;
		}

		String wText = tradeModified.getText();
		if (!wText.contains("WARNING")) {
			String warningMsg = String.format("<br>WARNING: %s is on WDR's banlist.", trader);
			String msg = wText + warningMsg;
			tradeModified.setText(msg);

			// check if this is the first time we've offset x/y
			if (tradeModified.getOriginalY() == tradeModifiedMe.getOriginalY()) {
				tradeModified.setOriginalY(tradeModified.getOriginalY() - 10);
				tradeModified.setOriginalX(tradeModified.getOriginalX() - 20);
			}
			tradeModified.setHidden(false);
			tradeModified.revalidate();
		}
	}

	private void alertPlayerWarning(String rsn, boolean notifyClear, boolean fc) {
		rsn = Text.toJagexName(rsn);
		Ban ban = banlistManager.get(rsn.toLowerCase());
		ChatMessageBuilder response = new ChatMessageBuilder();
		if (fc) {
			response.append("Friends chat member, ");
		}
		response.append(ChatColorType.HIGHLIGHT)
				.append(rsn)
				.append(ChatColorType.NORMAL);

		if (ban == null && !notifyClear) {
			return;
		} else if (ban == null) {
			response.append(" is not on the WDR banlist.");
		} else {
			response
					.append(" is on the WDR banlist list for: ")
					.append(ChatColorType.HIGHLIGHT)
					.append(ban.getCategory())
					.append(ChatColorType.NORMAL)
					.append(".");
		}

		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(response.build())
				.build());
	}

	private void colorFriendsChat() {
		Widget ccList = client.getWidget(WidgetInfo.FRIENDS_CHAT_LIST);
		if (ccList != null) {
			Widget[] players = ccList.getDynamicChildren();
			for (int i = 0; i < players.length; i += 3) {
				Widget player = players[i];
				if (player == null) {
					continue;
				}

				Ban ban = banlistManager.get(player.getText());
				if (ban == null) {
					continue;
				}

				player.setTextColor(config.playerTextColor().getRGB());
				player.revalidate();
			}
		}
	}
	private void colorRaidsSidePanel() {
		Widget raidsList = client.getWidget(WidgetID.RAIDING_PARTY_GROUP_ID, 10);
		if (raidsList != null) {
			colorTable(raidsList, 4);
		}
	}

	private void colorRaidsParty() {
		Widget table = client.getWidget(COX_PARTY_DETAILS_GROUP_ID, 11);
		if (table != null) {
			colorTable(table, 5);
		}
	}

	private void colorRaidsPartyList() {
		Widget list = client.getWidget(COX_PARTY_LIST_GROUP_ID, 14);
		if (list != null) {
			colorList(list, 3);
		}
	}

	private void colorTobHud() {
		// top right corner widget
		Widget hud = client.getWidget(28, 9);
		if (hud == null) {
			return;
		}

		String names = hud.getText(); // user1<br>user2<br>-<br>-<br>-
		if (!Strings.isNullOrEmpty(names)) {
			List<String> newNames = new ArrayList<>();
			for (String name : names.split("<br>")) {
				if (name.equals("-")) {
					newNames.add("-");
					continue;
				}

				String stripped = Text.removeTags(name);
				Ban ban = banlistManager.get(stripped);
				if (ban == null) {
					newNames.add(name);
					continue;
				}

				String colored = new ChatMessageBuilder().append(config.playerTextColor(), stripped).build();
				newNames.add(colored);
			}
			hud.setText(String.join("<br>", newNames));
		}
	}

	private void colorTobParty() {
		// current party members
		Widget table = client.getWidget(TOB_PARTY_DETAILS_GROUP_ID, 26);
		if (table != null) {
			// idx = 0 whole row, 1/12/23/... = rsn cell
			colorTable(table, 11);
		}

		// get current party application list
		table = client.getWidget(TOB_PARTY_DETAILS_GROUP_ID, 41);
		if (table != null) {
			// idx = 0 whole row, 0-18 = cells,1 = name
			colorTable(table, 18);
		}
	}

	private void colorTobPartyList() {
		Widget list = client.getWidget(TOB_PARTY_LIST_GROUP_ID, 16);
		if (list != null) {
			colorList(list, 3);
		}
	}

	private void colorTable(Widget table, int inc) {
		Widget[] players = table.getDynamicChildren();
		for (int i = 1; i < players.length; i += inc) {
			Widget player = players[i];
			if (player == null) {
				continue;
			}

			Ban ban = banlistManager.get(Text.removeTags(player.getText()));
			if (ban == null) {
				continue;
			}

			player.setTextColor(config.playerTextColor().getRGB());
			player.setText(Text.removeTags(player.getText()));
			player.revalidate();
		}
	}
	private void colorList(Widget list, int nameIdx) {
		for (Widget row : list.getStaticChildren()) {
			Widget name = row.getChild(nameIdx);
			if (name == null) {
				continue;
			}

			if (banlistManager.get(name.getText()) != null) {
				name.setTextColor(config.playerTextColor().getRGB());
				name.setText(Text.removeTags(name.getText())); // `col` is set on the name too
				name.revalidate();
			}
		}
	}
}