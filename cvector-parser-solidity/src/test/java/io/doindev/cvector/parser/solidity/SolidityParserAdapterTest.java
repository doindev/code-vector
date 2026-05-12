package io.doindev.cvector.parser.solidity;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolidityParserAdapterTest {

    @Test
    void emitsContractAndFunctions(@TempDir Path root) throws Exception {
        Path file = root.resolve("Token.sol");
        Files.writeString(file, """
                // SPDX-License-Identifier: MIT
                pragma solidity ^0.8.0;

                import "./IERC20.sol";

                contract Token {
                    uint256 public total;

                    function mint(address to, uint256 amount) public {
                        total += amount;
                    }

                    function burn(uint256 amount) external {
                        total -= amount;
                    }
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Contract")).anyMatch(s -> s.endsWith("Token"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("mint"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("burn"));
        assertThat(nodeFqNames(events, "Module")).contains("./IERC20.sol");
    }

    @Test
    void ignoresDeclarationsInComments(@TempDir Path root) throws Exception {
        Path file = root.resolve("Audit.sol");
        Files.writeString(file, """
                pragma solidity ^0.8.0;
                // contract CommentedContract { function fake() public {} }
                /* contract BlockCommentedContract {} */
                contract Real {
                    function realFn() public {}
                }
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Contract")).noneMatch(s -> s.toLowerCase().contains("commented"));
        assertThat(nodeFqNames(events, "Contract")).anyMatch(s -> s.endsWith("Real"));
        assertThat(nodeFqNames(events, "Method")).anyMatch(s -> s.endsWith("realFn"));
    }

    private static List<String> nodeFqNames(List<GraphEvent> events, String label) {
        return events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && label.equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new SolidityParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
