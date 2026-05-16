package dev.canxin.homescreenlayoutstudio.rules;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class RuleStore {
    private static final String ASSET_PATH = "rules/launcher_app_categories.json";
    private static final String DIR_NAME = "rules";
    private static final String FILE_NAME = "launcher_app_categories.json";

    private RuleStore() {
    }

    public static RuleSet load(Context context) throws Exception {
        File file = rulesFile(context);
        if (file.exists()) {
            return RuleSet.fromJson(readFile(file));
        }
        RuleSet defaults = loadDefaults(context);
        save(context, defaults);
        return defaults;
    }

    public static RuleSet loadDefaults(Context context) throws Exception {
        try (InputStream inputStream = context.getAssets().open(ASSET_PATH)) {
            return RuleSet.fromJson(readAll(inputStream));
        }
    }

    public static void save(Context context, RuleSet rules) throws Exception {
        File file = rulesFile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(rules.toPrettyJson().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static RuleSet reset(Context context) throws Exception {
        RuleSet defaults = loadDefaults(context);
        save(context, defaults);
        return defaults;
    }

    public static File rulesFile(Context context) {
        return new File(new File(context.getFilesDir(), DIR_NAME), FILE_NAME);
    }

    private static String readFile(File file) throws Exception {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            return readAll(inputStream);
        }
    }

    private static String readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }
}
