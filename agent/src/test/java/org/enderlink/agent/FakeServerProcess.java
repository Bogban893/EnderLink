package org.enderlink.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Тестовый процесс с тем же консольным контрактом, что у Vanilla сервера. */
public final class FakeServerProcess {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("fail")) {
            System.err.println("Startup failed");
            System.exit(7);
        }
        System.out.println("Fake server ready");
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("save-all flush")) System.out.println("Saved");
                if (line.equals("stop")) {
                    Files.writeString(Path.of("world", "level.dat"), "updated world");
                    System.out.println("Stopped");
                    return;
                }
            }
        }
    }
}
