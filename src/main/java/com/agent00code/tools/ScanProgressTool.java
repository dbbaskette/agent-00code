package com.agent00code.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tracks which CF orgs and spaces have been scanned across iterations.
 * The LLM calls these tools to check what's done and mark progress,
 * so it doesn't re-scan orgs when context resets between iterations.
 */
@Component
public class ScanProgressTool {

    private final Set<String> completedOrgs = new LinkedHashSet<>();
    private final Set<String> completedSpaces = new LinkedHashSet<>();

    @Tool(description = "Get the list of CF organizations and spaces already scanned. Call this at the start of each iteration to know what to skip.")
    public String getProgress() {
        if (completedOrgs.isEmpty() && completedSpaces.isEmpty()) {
            return "No orgs or spaces scanned yet. Start from the beginning.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Completed orgs (").append(completedOrgs.size()).append("): ");
        sb.append(String.join(", ", completedOrgs));
        sb.append("\nCompleted spaces (").append(completedSpaces.size()).append("): ");
        if (completedSpaces.size() > 20) {
            sb.append(completedSpaces.size()).append(" total");
        } else {
            sb.append(String.join(", ", completedSpaces));
        }
        return sb.toString();
    }

    @Tool(description = "Mark a CF organization as fully scanned (all its spaces, apps, and services have been collected and written to Sheets). Call this after writing an org's data to the spreadsheet.")
    public String markOrgComplete(
            @ToolParam(description = "The organization name that was fully scanned") String orgName) {
        completedOrgs.add(orgName);
        return "Marked org '" + orgName + "' as complete. " + completedOrgs.size() + " orgs done so far.";
    }

    @Tool(description = "Mark a CF space as scanned. Call this after scanning apps and services in a space.")
    public String markSpaceComplete(
            @ToolParam(description = "The org/space identifier, e.g. 'system/system'") String orgSpace) {
        completedSpaces.add(orgSpace);
        return "Marked space '" + orgSpace + "' as complete.";
    }

    @Tool(description = "Reset the scan progress. Call this to start a fresh scan from scratch.")
    public String resetProgress() {
        completedOrgs.clear();
        completedSpaces.clear();
        return "Progress reset. All orgs and spaces cleared.";
    }
}
