package com.huidu.farmersdelight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class BundledSparrowLibrariesTest {
    @TempDir Path directory;

    @Test void shippedJarRelocatesUiAndYamlAndRetainsLicensesAndProxyArchive() throws Exception {
        try (var jar = new ZipFile(System.getProperty("fd.pluginJar"))) {
            Set<String> names = jar.stream().map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet());
            assertTrue(names.contains("com/huidu/farmersdelight/libs/sparrow/ui/SparrowUI.class"));
            assertTrue(names.contains("com/huidu/farmersdelight/libs/sparrow/yaml/SparrowYaml.class"));
            assertTrue(names.contains("META-INF/licenses/sparrow-ui-Apache-2.0.txt"));
            assertTrue(names.contains("META-INF/licenses/sparrow-yaml-GPL-3.0.txt"));
            assertFalse(names.stream().anyMatch(name -> name.startsWith("net/momirealms/sparrow/")));
            assertTrue(jar.getEntry("sparrow-ui-proxy.jarinjar").getSize() > 0);
        }
        try (var api = new ZipFile(System.getProperty("fd.apiJar"))) {
            assertFalse(api.stream().anyMatch(entry -> entry.getName().contains("sparrow")));
        }
    }

    @Test void relocatedUiCanActuallyRelocateItsEmbeddedRuntimeProxy() throws Exception {
        Path pluginJar = Path.of(System.getProperty("fd.pluginJar"));
        String namespace = "com.huidu.farmersdelight.libs.sparrow.ui";
        byte[] proxy;
        try (var jar = new ZipFile(pluginJar.toFile())) {
            proxy = jar.getInputStream(jar.getEntry("sparrow-ui-proxy.jarinjar")).readAllBytes();
        }
        byte[] relocated;
        // Use the shipped copy, with no unrelocated test dependencies visible to its loader.
        try (var loader = new URLClassLoader(new java.net.URL[]{pluginJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> relocator = Class.forName(namespace + ".ProxyRelocator", true, loader);
            var method = relocator.getDeclaredMethod("relocate", byte[].class, String.class);
            method.setAccessible(true);
            relocated = (byte[]) method.invoke(null, proxy, namespace);
        }
        Set<String> names = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(relocated))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
        }
        assertTrue(names.contains(namespace.replace('.', '/') + "/proxy/BukkitProxy.class"));
        assertFalse(names.stream().anyMatch(name -> name.startsWith("net/momirealms/sparrow/ui/")));
        Path proxyJar = directory.resolve("proxy.jar");
        Files.write(proxyJar, relocated);
        try (var loader = new URLClassLoader(new java.net.URL[]{proxyJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            // Loading without initialization verifies the class namespace without requiring a live server.
            assertNotNull(Class.forName(namespace + ".proxy.BukkitProxy", false, loader));
        }
    }
}
