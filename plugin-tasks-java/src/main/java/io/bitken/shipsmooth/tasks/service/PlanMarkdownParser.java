package io.bitken.shipsmooth.tasks.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses plan-{N}.md narrative markdown into {@link XmlService.Task} records.
 *
 * <p>Split out from {@code XmlService} because Markdown parsing has nothing to do
 * with XML marshalling — and the regex patterns are stable while the JAXB schema
 * evolves on a different cadence.
 */
public class PlanMarkdownParser {

    private static final Pattern HEADING = Pattern.compile(
            "^###\\s+Task\\s+(\\d+):\\s+(.+?)(?:\\s+\\[(High|Medium|Low)\\])?\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Matches an optional "*Depends-on: 1,2,3*" line anywhere after the heading
    private static final Pattern DEPENDS_ON = Pattern.compile(
            "^\\*Depends-on:\\s*([\\d,\\s]+)\\*\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public List<XmlService.Task> parse(String markdown) {
        List<XmlService.Task> tasks = new ArrayList<>();
        Matcher matcher = HEADING.matcher(markdown);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2).trim();
            String risk = matcher.group(3) != null ? matcher.group(3).toLowerCase() : "";
            int searchEnd = Math.min(matcher.end() + 500, markdown.length());
            Matcher nextHeading = HEADING.matcher(markdown.substring(matcher.end(), searchEnd));
            int regionEnd = nextHeading.find() ? matcher.end() + nextHeading.start() : searchEnd;
            String region = markdown.substring(matcher.end(), regionEnd);
            Matcher depMatcher = DEPENDS_ON.matcher(region);
            String dependsOn = depMatcher.find() ? depMatcher.group(1).replaceAll("\\s", "") : "";
            tasks.add(new XmlService.Task(id, name, risk, dependsOn));
        }
        return tasks;
    }
}
