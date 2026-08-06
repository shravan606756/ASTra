package com.shravan.jcode_intelligence.cli;

import com.shravan.jcode_intelligence.cli.client.ApiClient;
import com.shravan.jcode_intelligence.cli.commands.ArchitectureCommand;
import com.shravan.jcode_intelligence.cli.commands.AskCommand;
import com.shravan.jcode_intelligence.cli.commands.CallsCommand;
import com.shravan.jcode_intelligence.cli.commands.ClassCommand;
import com.shravan.jcode_intelligence.cli.commands.ClearCommand;
import com.shravan.jcode_intelligence.cli.commands.DependenciesCommand;
import com.shravan.jcode_intelligence.cli.commands.DoctorCommand;
import com.shravan.jcode_intelligence.cli.commands.ExitCommand;
import com.shravan.jcode_intelligence.cli.commands.HelpCommand;
import com.shravan.jcode_intelligence.cli.commands.IndexCommand;
import com.shravan.jcode_intelligence.cli.commands.MethodCommand;
import com.shravan.jcode_intelligence.cli.client.ApiException;
import com.shravan.jcode_intelligence.cli.commands.RemoveCommand;
import com.shravan.jcode_intelligence.cli.commands.ReposCommand;
import com.shravan.jcode_intelligence.cli.commands.SearchCommand;
import com.shravan.jcode_intelligence.cli.commands.StatsCommand;
import com.shravan.jcode_intelligence.cli.commands.SummaryCommand;
import com.shravan.jcode_intelligence.cli.commands.DesignCommand;
import com.shravan.jcode_intelligence.cli.commands.UseCommand;
import com.shravan.jcode_intelligence.cli.commands.WorkflowCommand;
import com.shravan.jcode_intelligence.cli.shell.CommandDispatcher;
import com.shravan.jcode_intelligence.cli.shell.CommandParser;
import com.shravan.jcode_intelligence.cli.shell.InteractiveShell;
import com.shravan.jcode_intelligence.cli.shell.ShellContext;
import com.shravan.jcode_intelligence.cli.ui.ConsoleUI;

import java.util.List;

/**
 * Entry point for the ASTra interactive CLI.
 *
 * <p>Constructs all components manually (no Spring DI) and wires them
 * together. The CLI communicates with the ASTra backend over HTTP.
 */
public class AstraCli {

    public static void main(String[] args) {
        // -- UI and session state -------------------------------------------
        ConsoleUI consoleUI = new ConsoleUI();
        ShellContext context = new ShellContext();

        // -- API Client -----------------------------------------------------
        ApiClient apiClient = new ApiClient("http://localhost:8080");

        // -- Local commands (no API client needed) --------------------------
        ExitCommand exitCmd = new ExitCommand(consoleUI);
        ClearCommand clearCmd = new ClearCommand(consoleUI);
        UseCommand useCmd = new UseCommand(context, consoleUI);

        // -- API-backed commands --------------------------------------------
        DoctorCommand doctorCmd = new DoctorCommand(apiClient, consoleUI);
        ReposCommand reposCmd = new ReposCommand(apiClient, consoleUI);
        RemoveCommand removeCmd = new RemoveCommand(apiClient, context, consoleUI);
        StatsCommand statsCmd = new StatsCommand(apiClient, consoleUI);
        IndexCommand indexCmd = new IndexCommand(apiClient, consoleUI);
        AskCommand askCmd = new AskCommand(apiClient, context, consoleUI);
        ClassCommand classCmd = new ClassCommand(apiClient, context, consoleUI);
        MethodCommand methodCmd = new MethodCommand(apiClient, context, consoleUI);
        ArchitectureCommand archCmd = new ArchitectureCommand(apiClient, context, consoleUI);
        WorkflowCommand workflowCmd = new WorkflowCommand(apiClient, context, consoleUI);
        DependenciesCommand depCmd = new DependenciesCommand(apiClient, context, consoleUI);
        CallsCommand callsCmd = new CallsCommand(apiClient, context, consoleUI);
        SearchCommand searchCmd = new SearchCommand(apiClient, context, consoleUI);
        SummaryCommand summaryCmd = new SummaryCommand(apiClient, context, consoleUI);
        DesignCommand designCmd = new DesignCommand(apiClient, context, consoleUI);

        // -- Command infrastructure -----------------------------------------
        CommandParser parser = new CommandParser();
        CommandDispatcher dispatcher = new CommandDispatcher(
                List.of(exitCmd, clearCmd, useCmd, doctorCmd, reposCmd, removeCmd, statsCmd, indexCmd, askCmd,
                        classCmd, methodCmd, archCmd, workflowCmd, depCmd, callsCmd, searchCmd, summaryCmd, designCmd),
                consoleUI
        );

        // HelpCommand needs the dispatcher → create after, register late
        HelpCommand helpCmd = new HelpCommand(dispatcher, consoleUI);
        dispatcher.register(helpCmd);

        // -- Shell ----------------------------------------------------------
        InteractiveShell shell = new InteractiveShell(consoleUI, parser, dispatcher, context);
        shell.start();
    }
}
