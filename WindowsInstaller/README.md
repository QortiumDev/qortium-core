# Windows installer

## Prerequisites

* AdvancedInstaller v19.4 or better, and enterprise licence.
* This installer project was inherited from Qortal's open-source Advanced Installer setup, which currently (as of December 2024) only supports up to version 19. A Qortium-specific license or build process may be needed before release.
* Reach out to the Qortium maintainers for links to the installer install files and license.
* Installed AdoptOpenJDK v17 64bit, full JDK *not* JRE

## General build instructions

If this is your first time opening the `qortium.aip` file then you might need to adjust
configured paths, or create a dummy `D:` drive with the expected layout.

Opening the aip file from within a clone of the qortium repo also works, if you have a separate windows machine setup to do the build.

You May need to change the location of the 'jre64' files inside Advanced Installer, if it is set to a path that your build machine doesn't have. 

The Java Memory Arguments can be set manually, but as of December 2024 they have been reset back to system defaults. This should include G1GC Garbage Collector.

Typical build procedure:

* Place the `qortium.jar` file in `Install-Files\`
* Open AdvancedInstaller with qortium.aip file
* If releasing a new version, change version number in:
	+ "Product Details" side menu entry
	+ "Product Details" tab in "Product Details" pane
* Click away to a different side menu entry, e.g. "Resources" -> "Files and Folders"
* You should be prompted whether to generate a new product key, click "Generate New"
* Click "Build" button
* New EXE should be generated in `Qortium-SetupFiles\` folder with correct version number
