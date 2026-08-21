package dev.konacode.cli;

import dev.konacode.agent.Agent;
import dev.konacode.agent.AppendOnlyConversation;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.openai.OpenAiClient;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.Workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** The REPL: read a line, print the answer, repeat. */
public final class Main {

    private static final String SYSTEM_PROMPT = "You are konacode, a concise CLI assistant.";

    private Main() {
    }

    public static void main(String[] args) {
        OpenAiConfig config;
        try {
            config = OpenAiConfig.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Workspace workspace = Workspace.ofCurrentDirectory();
        Agent agent = new Agent(
                new OpenAiClient(config),
                ToolRegistry.of(
                        new ListFiles(workspace),
                        new ReadFile(workspace),
                        new EditFile(workspace)),
                new AllowAllPolicy(),
                new AppendOnlyConversation(new SystemMessage(SYSTEM_PROMPT)),
                new ConsoleToolCallListener(System.out),
                Agent.configuredMaxIterations());

        System.out.println();
        System.out.println("Chat with konacode (use 'ctrl-c' to quit)");
        System.out.println();

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        try {
            while (true) {
                System.out.print(Ansi.blue("You") + ": ");
                System.out.flush();

                String line = in.readLine();
                if (line == null) {
                    break;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                System.out.println(Ansi.green("konacode") + ": " + agent.respond(trimmed));
            }
        } catch (IOException e) {
            System.err.println("Input failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
