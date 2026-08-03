package com.example.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every file in the config repo parses.
 *
 * <p>Written after a real incident in Step 10d, and it is worth stating what that incident was,
 * because the shape generalises. A duplicate {@code httpclient} key was introduced into
 * {@code api-gateway.yml} — the file already had one, forty lines further down. The consequences, in
 * order:
 *
 * <ul>
 *   <li>the config server returned <b>500</b> for {@code /api-gateway/dev};
 *   <li>the <b>running</b> gateway pod carried on serving perfectly, because it read its configuration
 *       at startup and never again;
 *   <li>and the pods the autoscaler had just created could not start.
 * </ul>
 *
 * <p>So a broken configuration change was invisible until something needed to restart — and the thing
 * that needed to restart was the HPA responding to load. <b>The failure surfaced at peak traffic, in
 * new pods, while the old ones looked healthy.</b>
 *
 * <p>{@link ConfigServerContractTest} could not have caught it. That test asserts what the files
 * <i>say</i> — which service gets which datasource, which route points where — and to do so it asks a
 * running server, which by then has already failed to load the file. This one asserts only that they
 * can be read at all, which is the cheaper and more fundamental property.
 *
 * <p>A {@link TestFactory} rather than one loop, so a broken file is named in the failure report
 * instead of being "the first one that threw".
 */
class ConfigRepoParsesTest {

    // Relative to the config-server module, which is where Maven runs this test from. The same path
    // the running server uses via CONFIG_REPO, so the test cannot drift onto a different directory.
    private static final Path CONFIG_REPO = Path.of("..", "config-repo");

    @TestFactory
    @DisplayName("every file the config server serves is loadable YAML")
    Stream<DynamicTest> everyConfigFileParses() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(CONFIG_REPO)) {
            files = paths.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList();
        }

        // A guard on the guard. If the directory ever moves, `Files.list` returns nothing, every
        // dynamic test disappears, and a suite asserting nothing at all reports green — which is
        // exactly the failure mode this test exists to prevent, one level up.
        assertThat(files)
                .as("config-repo should hold every service's configuration; found none, so this "
                        + "test is looking at the wrong directory and is proving nothing")
                .hasSizeGreaterThanOrEqualTo(15);

        return files.stream().map(file -> DynamicTest.dynamicTest(
                file.getFileName().toString(),
                () -> assertThatCode(() -> new YamlPropertySourceLoader()
                        .load(file.getFileName().toString(), new FileSystemResource(file)))
                        // Named in the message as well as in the test title, because Surefire renders
                        // a dynamic test as `everyConfigFileParses()[4]` in the console summary and
                        // SnakeYAML's own error says `in 'reader', line 154` without a filename. A
                        // failure that identifies neither the file nor the line is a failure somebody
                        // has to reproduce before they can fix it.
                        .as("config-repo/%s must be loadable YAML", file.getFileName())
                        .doesNotThrowAnyException()));
    }
}
