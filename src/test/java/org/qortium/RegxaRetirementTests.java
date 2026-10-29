package org.qortium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RegxaRetirementTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> TYPE = new TypeReference<>() {};

    @Test
    public void shippedProfilesAndPackageExcludeTheRetiredSeed() throws Exception {
        assertFalse(Files.exists(Path.of("preview/settings-preview-seed.json")));
        assertFalse(Files.readString(Path.of("preview/package-release.sh")).contains("\"settings-preview-seed.json\""));
        for (String profile : List.of("preview/settings-preview.json", "preview/settings-preview-seed-netcup.json")) {
            Map<String, Object> settings = read(Path.of(profile));
            for (String key : List.of("initialPeers", "initialDataPeers")) {
                String peers = settings.get(key).toString();
                assertFalse(profile, peers.contains("146.103.42.59"));
                assertFalse(profile, peers.contains("3u25ana5"));
                assertFalse(profile, peers.contains("hg3seiuul"));
                assertTrue(profile, peers.contains("80.241.221.139"));
            }
        }
    }

    @Test
    public void untouchedReleasedPeerDefaultsAdvanceWithoutRetiredEndpoints() throws Exception {
        Map<String, Object> old = read(Path.of("src/test/resources/mergesettings/settings-preview-v1.8.0.json"));
        Map<String, Object> current = read(Path.of("preview/settings-preview.json"));
        Path root = Files.createTempDirectory("regxa-upgrade-");
        Path template = root.resolve("template.json"), snapshot = root.resolve("snapshot.json"), local = root.resolve("local.json");
        MAPPER.writeValue(template.toFile(), current);
        MAPPER.writeValue(snapshot.toFile(), old);
        MAPPER.writeValue(local.toFile(), old);
        MergeSettings.merge(template, snapshot, local);
        Map<String, Object> merged = read(local);
        for (String key : List.of("initialPeers", "initialDataPeers")) {
            assertEquals(current.get(key), merged.get(key));
            assertFalse(merged.get(key).toString().contains("146.103.42.59"));
            assertFalse(merged.get(key).toString().contains("3u25ana5"));
            assertFalse(merged.get(key).toString().contains("hg3seiuul"));
        }
        byte[] once = Files.readAllBytes(local);
        MergeSettings.merge(template, snapshot, local);
        assertArrayEquals(once, Files.readAllBytes(local));
    }

    @Test
    public void customizedPeerListsRemainOperatorOwned() throws Exception {
        Map<String, Object> old = read(Path.of("src/test/resources/mergesettings/settings-preview-v1.8.0.json"));
        Map<String, Object> localValues = new LinkedHashMap<>(old);
        for (String key : List.of("initialPeers", "initialDataPeers")) {
            List<Object> custom = new ArrayList<>((List<?>) old.get(key));
            custom.add(key.equals("initialPeers") ? "198.51.100.30:24892" : "198.51.100.30:24894");
            localValues.put(key, custom);
        }
        Path root = Files.createTempDirectory("regxa-custom-upgrade-");
        Path snapshot = root.resolve("snapshot.json"), local = root.resolve("local.json");
        MAPPER.writeValue(snapshot.toFile(), old);
        MAPPER.writeValue(local.toFile(), localValues);
        MergeSettings.merge(Path.of("preview/settings-preview.json"), snapshot, local);
        for (String key : List.of("initialPeers", "initialDataPeers"))
            assertEquals(localValues.get(key), read(local).get(key));
    }

    @Test
    public void retiredBashFlagsFailBeforeCreatingRuntimeState() throws Exception {
        for (String flag : List.of("--seed", "--seed-regxa")) {
            Path root = Files.createTempDirectory("regxa-launch-");
            Path runtime = root.resolve("must-not-exist");
            Process process = new ProcessBuilder("bash", "preview/start.sh", flag, "--runtime-dir=" + runtime)
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 2, process.exitValue());
            assertTrue(output, output.contains("retired"));
            assertFalse(Files.exists(runtime));
        }
    }

    @Test
    public void retiredPowerShellFlagsFailBeforeCreatingRuntimeState() throws Exception {
        try {
            new ProcessBuilder("pwsh", "-NoProfile", "-Command", "exit 0").start().waitFor();
        } catch (java.io.IOException unavailable) {
            org.junit.Assume.assumeNoException("PowerShell is not installed", unavailable);
        }
        for (String flag : List.of("--seed", "--seed-regxa")) {
            Path root = Files.createTempDirectory("regxa-powershell-launch-");
            Path runtime = root.resolve("must-not-exist");
            Process process = new ProcessBuilder("pwsh", "-NoProfile", "-File", "preview/start.ps1", flag,
                    "--runtime-dir=" + runtime).redirectErrorStream(true).start();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 2, process.exitValue());
            assertTrue(output, output.contains("retired"));
            assertFalse(Files.exists(runtime));
        }
    }

    private static Map<String, Object> read(Path path) throws Exception {
        return MAPPER.readValue(Files.readAllBytes(path), TYPE);
    }
}
