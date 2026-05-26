package org.qortium.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSerializable;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LinuxStatusNotifierTray implements NodeTray {

	private static final Logger LOGGER = LogManager.getLogger(LinuxStatusNotifierTray.class);

	private static final String STATUS_NOTIFIER_WATCHER = "org.kde.StatusNotifierWatcher";
	private static final String STATUS_NOTIFIER_WATCHER_PATH = "/StatusNotifierWatcher";
	private static final String STATUS_NOTIFIER_ITEM_INTERFACE = "org.kde.StatusNotifierItem";
	private static final String DBUSMENU_INTERFACE = "com.canonical.dbusmenu";
	private static final String NOTIFICATIONS_SERVICE = "org.freedesktop.Notifications";
	private static final String NOTIFICATIONS_PATH = "/org/freedesktop/Notifications";
	private static final String ITEM_PATH = "/StatusNotifierItem";
	private static final String MENU_PATH = "/StatusNotifierItem/Menu";
	private static final String ICON_PIXMAP_SIGNATURE = "a(iiay)";
	private static final String TOOLTIP_SIGNATURE = "(sa(iiay)ss)";
	private static final String MENU_ITEM_SIGNATURE = "(ia{sv}av)";
	private static final String TOOLTIP_TITLE = "Qortium";
	private static final String ICON_THEME_INDEX = "[Icon Theme]\n"
			+ "Name=Qortium Tray\n"
			+ "Comment=Qortium tray icons\n"
			+ "Directories=32x32/status,32x32/apps\n"
			+ "\n"
			+ "[32x32/status]\n"
			+ "Size=32\n"
			+ "Context=Status\n"
			+ "Type=Fixed\n"
			+ "\n"
			+ "[32x32/apps]\n"
			+ "Size=32\n"
			+ "Context=Applications\n"
			+ "Type=Fixed\n";

	private final DBusConnection connection;
	private final String serviceName;
	private final String iconThemePath;
	private final StatusNotifierItemObject itemObject;
	private final DBusMenuObject menuObject;
	private volatile TrayIconState iconState = TrayIconState.SYNCED;
	private volatile String tooltip = "";
	private volatile boolean menuAvailable;
	private volatile boolean disposed;

	private LinuxStatusNotifierTray(DBusConnection connection, String serviceName, String iconThemePath) {
		this.connection = connection;
		this.serviceName = serviceName;
		this.iconThemePath = iconThemePath == null ? "" : iconThemePath;
		this.itemObject = new StatusNotifierItemObject();
		this.menuObject = new DBusMenuObject();
	}

	static NodeTray create() {
		if (GraphicsEnvironment.isHeadless() || !isLinux())
			return null;

		DBusConnection connection = null;
		try {
			connection = DBusConnectionBuilder.forSessionBus().withShared(false).build();

			DBus dbus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
			if (!dbus.NameHasOwner(STATUS_NOTIFIER_WATCHER)) {
				connection.disconnect();
				return null;
			}

			String serviceName = buildServiceName();
			LinuxStatusNotifierTray tray = new LinuxStatusNotifierTray(connection, serviceName, prepareIconThemePath());
			connection.requestBusName(serviceName);
			connection.exportObject(ITEM_PATH, tray.itemObject);

			try {
				connection.exportObject(MENU_PATH, tray.menuObject);
				tray.menuAvailable = true;
			} catch (Throwable e) {
				LOGGER.debug("Linux native tray menu is unavailable: {}", e.getMessage());
			}

			StatusNotifierWatcher watcher = connection.getRemoteObject(STATUS_NOTIFIER_WATCHER, STATUS_NOTIFIER_WATCHER_PATH,
					StatusNotifierWatcher.class);
			watcher.RegisterStatusNotifierItem(serviceName);

			LOGGER.info("Launched Linux StatusNotifierItem tray icon");
			return tray;
		} catch (Throwable e) {
			LOGGER.info("Linux native system tray is unavailable: {}", e.getMessage());
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (RuntimeException ignored) {
				}
			}
			return null;
		}
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase().contains("linux");
	}

	private static String buildServiceName() {
		long pid;
		try {
			pid = ProcessHandle.current().pid();
		} catch (RuntimeException e) {
			pid = System.currentTimeMillis();
		}

		return "org.freedesktop.StatusNotifierItem.Qortium.Instance" + pid;
	}

	private static String prepareIconThemePath() {
		Path iconThemePath = getIconThemePath();

		try {
			Files.createDirectories(iconThemePath);
			Files.createDirectories(iconThemePath.resolve("hicolor/32x32/status"));
			Files.createDirectories(iconThemePath.resolve("hicolor/32x32/apps"));
			Files.writeString(iconThemePath.resolve("hicolor/index.theme"), ICON_THEME_INDEX, StandardCharsets.UTF_8);

			for (TrayIconState iconState : TrayIconState.values()) {
				writeIconResource(iconState, iconThemePath.resolve(iconState.getIconName() + ".png"));
				writeIconResource(iconState, iconThemePath.resolve("hicolor/32x32/status/" + iconState.getIconName() + ".png"));
				writeIconResource(iconState, iconThemePath.resolve("hicolor/32x32/apps/" + iconState.getIconName() + ".png"));
			}

			return iconThemePath.toAbsolutePath().toString();
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Unable to prepare Linux native tray icon theme: {}", e.getMessage());
			return "";
		}
	}

	private static Path getIconThemePath() {
		String xdgCacheHome = System.getenv("XDG_CACHE_HOME");
		if (xdgCacheHome != null && !xdgCacheHome.isBlank())
			return Paths.get(xdgCacheHome, "qortium", "tray-icons");

		String userHome = System.getProperty("user.home");
		if (userHome != null && !userHome.isBlank())
			return Paths.get(userHome, ".cache", "qortium", "tray-icons");

		return Paths.get(System.getProperty("java.io.tmpdir"), "qortium-tray-icons");
	}

	private static void writeIconResource(TrayIconState iconState, Path destination) throws IOException {
		try (InputStream in = Gui.class.getResourceAsStream("/images/" + iconState.getResourceName())) {
			if (in == null)
				throw new IOException("Missing tray icon resource: " + iconState.getResourceName());

			Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	@Override
	public boolean isAvailable() {
		return !this.disposed && this.connection.isConnected();
	}

	@Override
	public void showMessage(String caption, String text, TrayMessageType messageType) {
		if (!isAvailable())
			return;

		try {
			DBus dbus = this.connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
			if (!dbus.NameHasOwner(NOTIFICATIONS_SERVICE))
				return;

			DesktopNotifications notifications = this.connection.getRemoteObject(NOTIFICATIONS_SERVICE, NOTIFICATIONS_PATH,
					DesktopNotifications.class);
			notifications.Notify("Qortium", new UInt32(0), "qortium", caption, text, new String[0],
					Collections.emptyMap(), 10_000);
		} catch (Throwable e) {
			LOGGER.debug("Unable to show Linux desktop notification: {}", e.getMessage());
		}
	}

	@Override
	public void setToolTipText(String text) {
		this.tooltip = text == null ? "" : text;
		emitStatusNotifierSignal("NewToolTip");
	}

	@Override
	public void setTrayIcon(TrayIconState iconState) {
		if (iconState != null) {
			this.iconState = iconState;
			emitStatusNotifierSignal("NewIcon");
		}
	}

	@Override
	public void dispose() {
		if (this.disposed)
			return;

		this.disposed = true;

		try {
			this.connection.unExportObject(MENU_PATH);
			this.connection.unExportObject(ITEM_PATH);
			this.connection.releaseBusName(this.serviceName);
		} catch (Throwable e) {
			LOGGER.debug("Unable to release Linux native tray resources: {}", e.getMessage());
		} finally {
			try {
				this.connection.disconnect();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private Map<String, Variant<?>> getStatusNotifierProperties() {
		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		properties.put("Category", variant("ApplicationStatus"));
		properties.put("Id", variant("qortium"));
		properties.put("Title", variant("Qortium Core"));
		properties.put("Status", variant("Active"));
		properties.put("WindowId", variant(new UInt32(0)));
		properties.put("IconName", variant(this.iconState.getIconName()));
		properties.put("IconPixmap", variant(createIconPixmaps(this.iconState), ICON_PIXMAP_SIGNATURE));
		properties.put("OverlayIconName", variant(""));
		properties.put("OverlayIconPixmap", variant(Collections.emptyList(), ICON_PIXMAP_SIGNATURE));
		properties.put("AttentionIconName", variant(""));
		properties.put("AttentionIconPixmap", variant(Collections.emptyList(), ICON_PIXMAP_SIGNATURE));
		properties.put("AttentionMovieName", variant(""));
		properties.put("ToolTip", variant(createToolTip(), TOOLTIP_SIGNATURE));
		properties.put("IconThemePath", variant(this.iconThemePath));
		properties.put("ItemIsMenu", variant(this.menuAvailable));
		if (this.menuAvailable)
			properties.put("Menu", variant(new DBusPath(MENU_PATH)));
		return properties;
	}

	private List<IconPixmap> createIconPixmaps(TrayIconState iconState) {
		BufferedImage image = Gui.loadImage(iconState.getResourceName());
		if (image == null)
			return Collections.emptyList();

		int width = image.getWidth();
		int height = image.getHeight();
		byte[] data = new byte[width * height * 4];
		int index = 0;
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int argb = image.getRGB(x, y);
				data[index++] = (byte) ((argb >>> 24) & 0xff);
				data[index++] = (byte) ((argb >>> 16) & 0xff);
				data[index++] = (byte) ((argb >>> 8) & 0xff);
				data[index++] = (byte) (argb & 0xff);
			}
		}

		return Collections.singletonList(new IconPixmap(width, height, data));
	}

	private ToolTip createToolTip() {
		return new ToolTip(this.iconState.getIconName(), createIconPixmaps(this.iconState),
				getToolTipTitle(this.tooltip), getToolTipDescription(this.tooltip));
	}

	static String getToolTipTitle(String tooltip) {
		String firstLine = getToolTipLine(tooltip, 0);
		if (firstLine.isEmpty())
			return TOOLTIP_TITLE;

		return String.format("%s - %s", TOOLTIP_TITLE, firstLine);
	}

	static String getToolTipDescription(String tooltip) {
		String normalizedTooltip = normalizeToolTip(tooltip);
		if (normalizedTooltip.isEmpty())
			return "";

		int lineBreakIndex = normalizedTooltip.indexOf('\n');
		if (lineBreakIndex < 0)
			return "";

		return normalizedTooltip.substring(lineBreakIndex + 1).trim();
	}

	private static String getToolTipLine(String tooltip, int lineIndex) {
		String[] lines = normalizeToolTip(tooltip).split("\\n", -1);
		if (lineIndex >= lines.length)
			return "";

		return lines[lineIndex].trim();
	}

	private static String normalizeToolTip(String tooltip) {
		return tooltip == null ? "" : tooltip.replace("\r\n", "\n").replace('\r', '\n').trim();
	}

	private void emitStatusNotifierSignal(String signalName) {
		if (!isAvailable())
			return;

		try {
			this.connection.sendMessage(new DBusSignal(this.serviceName, ITEM_PATH, STATUS_NOTIFIER_ITEM_INTERFACE,
					signalName, ""));
		} catch (Throwable e) {
			LOGGER.debug("Unable to emit Linux native tray signal {}: {}", signalName, e.getMessage());
		}
	}

	private static Variant<?> variant(Object value) {
		return new Variant<>(value);
	}

	private static Variant<?> variant(Object value, String signature) {
		return new Variant<>(value, signature);
	}

	private Map<String, Variant<?>> getMenuProperties() {
		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		properties.put("Version", variant(new UInt32(4)));
		properties.put("TextDirection", variant("ltr"));
		properties.put("Status", variant("normal"));
		properties.put("IconThemePath", variant(new String[0]));
		return properties;
	}

	private DBusMenuItem getMenuLayout(int parentId, int recursionDepth) {
		if (parentId > 0)
			return getMenuItem(parentId, Collections.emptyList());

		List<Variant<DBusMenuItem>> children = new ArrayList<>();
		if (recursionDepth != 0) {
			for (TrayMenuAction action : TrayActions.createMenuActions(null))
				children.add(menuItemVariant(getMenuItem(action.getId(), Collections.emptyList())));
		}

		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		properties.put("children-display", variant("submenu"));
		return new DBusMenuItem(0, properties, children);
	}

	private DBusMenuItem getMenuItem(int id, List<Variant<DBusMenuItem>> children) {
		TrayMenuAction action = findAction(id);
		Map<String, Variant<?>> properties = new LinkedHashMap<>();
		if (action != null) {
			properties.put("label", variant(action.getLabel()));
			properties.put("enabled", variant(Boolean.TRUE));
			properties.put("visible", variant(Boolean.TRUE));
			properties.put("type", variant("standard"));
		}

		return new DBusMenuItem(id, properties, children);
	}

	@SuppressWarnings("unchecked")
	private static Variant<DBusMenuItem> menuItemVariant(DBusMenuItem menuItem) {
		return (Variant<DBusMenuItem>) variant(menuItem, MENU_ITEM_SIGNATURE);
	}

	private TrayMenuAction findAction(int id) {
		for (TrayMenuAction action : TrayActions.createMenuActions(null))
			if (action.getId() == id)
				return action;

		return null;
	}

	private Map<String, Variant<?>> filterProperties(Map<String, Variant<?>> properties, String[] requestedNames) {
		if (requestedNames == null || requestedNames.length == 0)
			return properties;

		Map<String, Variant<?>> filtered = new LinkedHashMap<>();
		for (String name : requestedNames) {
			Variant<?> value = properties.get(name);
			if (value != null)
				filtered.put(name, value);
		}
		return filtered;
	}

	private final class StatusNotifierItemObject implements StatusNotifierItem, Properties {

		@Override
		public String getObjectPath() {
			return ITEM_PATH;
		}

		@Override
		public void ContextMenu(int x, int y) {
		}

		@Override
		public void Activate(int x, int y) {
		}

		@Override
		public void SecondaryActivate(int x, int y) {
		}

		@Override
		public void Scroll(int delta, String orientation) {
		}

		@Override
		@SuppressWarnings("unchecked")
		public <A> A Get(String interfaceName, String propertyName) {
			if (!STATUS_NOTIFIER_ITEM_INTERFACE.equals(interfaceName))
				return null;

			Variant<?> value = getStatusNotifierProperties().get(propertyName);
			return value == null ? null : (A) value.getValue();
		}

		@Override
		public <A> void Set(String interfaceName, String propertyName, A value) {
		}

		@Override
		public Map<String, Variant<?>> GetAll(String interfaceName) {
			if (!STATUS_NOTIFIER_ITEM_INTERFACE.equals(interfaceName))
				return Collections.emptyMap();

			return getStatusNotifierProperties();
		}
	}

	private final class DBusMenuObject implements DBusMenu, Properties {

		@Override
		public String getObjectPath() {
			return MENU_PATH;
		}

		@Override
		public DBusMenuLayout GetLayout(int parentId, int recursionDepth, String[] propertyNames) {
			return new DBusMenuLayout(new UInt32(1), getMenuLayout(parentId, recursionDepth));
		}

		@Override
		public List<DBusMenuPropertyGroup> GetGroupProperties(int[] ids, String[] propertyNames) {
			if (ids == null)
				return Collections.emptyList();

			List<DBusMenuPropertyGroup> groups = new ArrayList<>();
			for (int id : ids) {
				DBusMenuItem item = getMenuItem(id, Collections.emptyList());
				groups.add(new DBusMenuPropertyGroup(id, filterProperties(item.properties, propertyNames)));
			}
			return groups;
		}

		@Override
		public Variant<?> GetProperty(int id, String name) {
			DBusMenuItem item = getMenuItem(id, Collections.emptyList());
			Variant<?> value = item.properties.get(name);
			return value == null ? variant("") : value;
		}

		@Override
		public void Event(int id, String eventId, Variant<?> data, UInt32 timestamp) {
			if (!"clicked".equals(eventId))
				return;

			TrayMenuAction action = findAction(id);
			if (action != null)
				action.run();
		}

		@Override
		public int[] EventGroup(List<DBusMenuEvent> events) {
			if (events == null)
				return new int[0];

			for (DBusMenuEvent event : events)
				Event(event.id, event.eventId, event.data, event.timestamp);

			return new int[0];
		}

		@Override
		public boolean AboutToShow(int id) {
			return false;
		}

		@Override
		public DBusMenuAboutToShowGroup AboutToShowGroup(int[] ids) {
			return new DBusMenuAboutToShowGroup(new int[0], new int[0]);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <A> A Get(String interfaceName, String propertyName) {
			if (!DBUSMENU_INTERFACE.equals(interfaceName))
				return null;

			Variant<?> value = getMenuProperties().get(propertyName);
			return value == null ? null : (A) value.getValue();
		}

		@Override
		public <A> void Set(String interfaceName, String propertyName, A value) {
		}

		@Override
		public Map<String, Variant<?>> GetAll(String interfaceName) {
			if (!DBUSMENU_INTERFACE.equals(interfaceName))
				return Collections.emptyMap();

			return getMenuProperties();
		}
	}

	@DBusInterfaceName("org.kde.StatusNotifierWatcher")
	public interface StatusNotifierWatcher extends DBusInterface {
		void RegisterStatusNotifierItem(String service);
	}

	@DBusInterfaceName("org.kde.StatusNotifierItem")
	public interface StatusNotifierItem extends DBusInterface {
		void ContextMenu(int x, int y);

		void Activate(int x, int y);

		void SecondaryActivate(int x, int y);

		void Scroll(int delta, String orientation);
	}

	@DBusInterfaceName("org.freedesktop.Notifications")
	public interface DesktopNotifications extends DBusInterface {
		UInt32 Notify(String appName, UInt32 replacesId, String appIcon, String summary, String body, String[] actions,
				Map<String, Variant<?>> hints, int expireTimeout);
	}

	@DBusInterfaceName("com.canonical.dbusmenu")
	public interface DBusMenu extends DBusInterface {
		DBusMenuLayout GetLayout(int parentId, int recursionDepth, String[] propertyNames);

		List<DBusMenuPropertyGroup> GetGroupProperties(int[] ids, String[] propertyNames);

		Variant<?> GetProperty(int id, String name);

		void Event(int id, String eventId, Variant<?> data, UInt32 timestamp);

		int[] EventGroup(List<DBusMenuEvent> events);

		boolean AboutToShow(int id);

		DBusMenuAboutToShowGroup AboutToShowGroup(int[] ids);
	}

	public static final class IconPixmap extends Struct {
		@Position(0)
		public final int width;
		@Position(1)
		public final int height;
		@Position(2)
		public final byte[] data;

		public IconPixmap(int width, int height, byte[] data) {
			this.width = width;
			this.height = height;
			this.data = data;
		}
	}

	public static final class ToolTip extends Struct {
		@Position(0)
		public final String iconName;
		@Position(1)
		public final List<IconPixmap> iconPixmap;
		@Position(2)
		public final String title;
		@Position(3)
		public final String description;

		public ToolTip(String iconName, List<IconPixmap> iconPixmap, String title, String description) {
			this.iconName = iconName;
			this.iconPixmap = iconPixmap;
			this.title = title;
			this.description = description;
		}
	}

	public static final class DBusMenuLayout implements DBusSerializable {
		public UInt32 revision;
		public DBusMenuItem layout;

		public DBusMenuLayout() {
		}

		public DBusMenuLayout(UInt32 revision, DBusMenuItem layout) {
			this.revision = revision;
			this.layout = layout;
		}

		public void deserialize(UInt32 revision, DBusMenuItem layout) {
			this.revision = revision;
			this.layout = layout;
		}

		@Override
		public Object[] serialize() throws DBusException {
			return new Object[] { this.revision, this.layout };
		}
	}

	public static final class DBusMenuItem extends Struct {
		@Position(0)
		public final int id;
		@Position(1)
		public final Map<String, Variant<?>> properties;
		@Position(2)
		public final List<Variant<DBusMenuItem>> children;

		public DBusMenuItem(int id, Map<String, Variant<?>> properties, List<Variant<DBusMenuItem>> children) {
			this.id = id;
			this.properties = properties == null ? new HashMap<>() : properties;
			this.children = children == null ? Collections.emptyList() : children;
		}
	}

	public static final class DBusMenuPropertyGroup extends Struct {
		@Position(0)
		public final int id;
		@Position(1)
		public final Map<String, Variant<?>> properties;

		public DBusMenuPropertyGroup(int id, Map<String, Variant<?>> properties) {
			this.id = id;
			this.properties = properties == null ? Collections.emptyMap() : properties;
		}
	}

	public static final class DBusMenuEvent extends Struct {
		@Position(0)
		public final int id;
		@Position(1)
		public final String eventId;
		@Position(2)
		public final Variant<?> data;
		@Position(3)
		public final UInt32 timestamp;

		public DBusMenuEvent(int id, String eventId, Variant<?> data, UInt32 timestamp) {
			this.id = id;
			this.eventId = eventId;
			this.data = data;
			this.timestamp = timestamp;
		}
	}

	public static final class DBusMenuAboutToShowGroup implements DBusSerializable {
		public int[] updatesNeeded;
		public int[] idErrors;

		public DBusMenuAboutToShowGroup() {
		}

		public DBusMenuAboutToShowGroup(int[] updatesNeeded, int[] idErrors) {
			this.updatesNeeded = updatesNeeded;
			this.idErrors = idErrors;
		}

		public void deserialize(int[] updatesNeeded, int[] idErrors) {
			this.updatesNeeded = updatesNeeded;
			this.idErrors = idErrors;
		}

		@Override
		public Object[] serialize() throws DBusException {
			return new Object[] { this.updatesNeeded, this.idErrors };
		}
	}
}
