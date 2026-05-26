package org.qortium.gui;

import org.junit.Test;

import java.awt.Dimension;
import java.awt.Image;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class GuiIconTests {

	@Test
	public void testAppIconsLoad() {
		List<Image> icons = Gui.loadAppIcons();

		assertFalse("Expected at least one Qortium app icon", icons.isEmpty());
		assertTrue("Expected all Qortium app icons to load", icons.stream().allMatch(icon -> icon != null));

		Set<Dimension> sizes = icons.stream()
				.map(icon -> new Dimension(icon.getWidth(null), icon.getHeight(null)))
				.collect(Collectors.toSet());

		assertTrue("Expected 16x16 app icon", sizes.contains(new Dimension(16, 16)));
		assertTrue("Expected 64x64 app icon", sizes.contains(new Dimension(64, 64)));
		assertTrue("Expected at least one large app icon",
				sizes.stream().anyMatch(size -> size.width >= 128 && size.height >= 128));
	}

	@Test
	public void testIconApplicationHelpersAreSafeWithoutWindows() {
		Gui.applyWindowIcon(null);
		Gui.applyTaskbarIcon();
	}

	@Test
	public void testTrayIconsLoad() {
		for (TrayIconState iconState : TrayIconState.values()) {
			Image image = Gui.loadImage(iconState.getResourceName());

			assertNotNull("Expected tray icon to load: " + iconState.name(), image);
			assertEquals("Expected 32px tray icon width: " + iconState.name(), 32, image.getWidth(null));
			assertEquals("Expected 32px tray icon height: " + iconState.name(), 32, image.getHeight(null));
		}
	}
}
