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

    @Tool(description = "Get the list of CF organizations already scanned in this run. Call this to know where to resume. IMPORTANT: After you finish scanning an org and writing its data to Sheets, call markOrgComplete with the org name.")
    public String getProgress() {
        if (completedOrgs.isEmpty()) {
            return "No orgs scanned yet. Start from the beginning. Remember to call markOrgComplete after each org.";
        }
        return "Completed orgs (" + completedOrgs.size() + "): " +
                String.join(", ", completedOrgs) +
                "\nSkip these and continue with the next org. Call markOrgComplete when done with each org.";
    }

    @Tool(description = "Mark a CF organization as fully scanned (all its spaces, apps, and services have been collected and written to Sheets). Call this after writing an org's data to the spreadsheet.")
    public String markOrgComplete(
            @ToolParam(description = "The organization name that was fully scanned") String orgName) {
        completedOrgs.add(orgName);
        return "Marked org '" + orgName + "' as complete. " + completedOrgs.size() + " orgs done so far.";
    }

    @Tool(description = "Reset the scan progress. Call this to start a fresh scan from scratch.")
    public String resetProgress() {
        completedOrgs.clear();
        return "Progress reset. All orgs cleared.";
    }
}
