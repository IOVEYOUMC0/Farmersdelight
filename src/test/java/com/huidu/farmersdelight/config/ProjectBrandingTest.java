package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectBrandingTest {
    @TempDir Path root;

    @Test void updatesOldMessagesAndPreservesCustomTextAndIdentifiers() throws Exception {
        Path lang = Files.createDirectory(root.resolve("lang")).resolve("zh_cn.yml");
        Files.writeString(lang, "prefix: '[FarmersDelight] 自定义文本'\nid: farmersdelight:cooking_pot\n");
        ProjectBranding.migratePluginData(root);
        assertEquals("prefix: '[Farmersdelight-Plugin-Pro] 自定义文本'\nid: farmersdelight:cooking_pot\n", Files.readString(lang));
        String updated = Files.readString(lang);
        ProjectBranding.migratePluginData(root);
        assertEquals(updated, Files.readString(lang));
    }

    @Test void updatesPackAuthorsAndAllLanguageCategoryNamesOnce() throws Exception {
        Files.writeString(root.resolve("pack.yml"), "author: HuiDu_OwO\r\nnamespace: farmersdelight\r\ndescription: Farmer's Delight\r\n");
        Path translations = Files.createDirectory(root.resolve("configuration")).resolve("translations.yml");
        Files.writeString(translations, "en_us:\n  farmersdelight.category.root: \"Farmer's Delight\"\nzh_cn:\n  farmersdelight.category.root: \"农夫乐事\"\n");
        assertEquals(2, ProjectBranding.migrateCraftEnginePack(root));
        assertTrue(Files.readString(root.resolve("pack.yml")).contains("author: HuiDu_OwO, ydxc2009"));
        assertTrue(Files.readString(root.resolve("pack.yml")).contains("namespace: farmersdelight"));
        assertEquals(2, Files.readString(translations).lines().filter(line -> line.contains("Farmersdelight-Plugin-Pro")).count());
        assertEquals(0, ProjectBranding.migrateCraftEnginePack(root));
    }
}
