//! Port of `PlanMarkdownParser`: `plan-{N}.md` narrative markdown →
//! [`ParsedTask`] records. The regex patterns are the spec (they are stable
//! while the XML schema evolves on a different cadence).

/// One `### Task N: name [Risk]` heading with its optional
/// `*Depends-on: 1,2*` line.
#[derive(Debug, PartialEq, Eq)]
pub struct ParsedTask {
    pub id: u32,
    pub name: String,
    /// Lowercased, or `""` when the heading has no risk tag.
    pub risk: String,
    /// Comma-separated ids with whitespace stripped, or `""`.
    pub depends_on: String,
}

use std::sync::LazyLock;

use regex::Regex;

static HEADING: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?im)^###\s+Task\s+(\d+):\s+(.+?)(?:\s+\[(High|Medium|Low)\])?\s*$").unwrap()
});

/// Matches an optional `*Depends-on: 1,2,3*` line anywhere after the heading.
static DEPENDS_ON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?im)^\*Depends-on:\s*([\d,\s]+)\*\s*$").unwrap());

/// The Java parser caps each task's search region at heading end + 500 —
/// a depends-on line beyond the window is silently dropped (PB-352).
const REGION_CAP: usize = 500;

/// The canonical heading grammar, quoted verbatim in every diagnostic.
const GRAMMAR: &str = "### Task N: Name [High|Medium|Low]";

// ── near-miss patterns ──────────────────────────────────────────────────────
// Java matches these with String.matches()/Matcher.matches(), which is a FULL
// match; the Rust equivalents are anchored and applied to one already
// trailing-trimmed line, so `^`/`$` mean exactly the same thing.

/// [`HEADING`] applied to a single line rather than the whole document.
static HEADING_LINE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^###\s+Task\s+(\d+):\s+(.+?)(?:\s+\[(High|Medium|Low)\])?\s*$").unwrap()
});

/// A line that structurally attempts a task heading at any level.
static ANY_LEVEL_TASK_HEADING: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^(#{1,6})\s+Task\s+(.*)$").unwrap());

/// Requires `:` or a dash after the number, so bold prose mentioning a task
/// (`**Task 1 de-risk** built …`) is not read as an attempted heading.
static BOLD_TASK_HEADING: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^\*\*\s*Task\s+\d+\s*[:\-—].*$").unwrap());

/// A valid heading whose name swallowed a trailing `[tag]` because the tag is
/// not a recognised risk level.
static TRAILING_TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^.*\[([A-Za-z]+)\]$").unwrap());

/// A line attempting a depends-on marker but missing the strict grammar. The
/// colon is required so wrapped prose beginning "depends on; …" is not
/// mistaken for an attempt.
static DEPENDS_ON_ATTEMPT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^\*?\s*Depends[-\s]?on\s*:.*$").unwrap());

/// [`DEPENDS_ON`] applied to a single line.
static DEPENDS_ON_LINE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^\*Depends-on:\s*([\d,\s]+)\*\s*$").unwrap());

/// Deliberate no-dependency markers, historically widespread: `*Depends-on:*`
/// and `*Depends-on: none …*`. Valid no-ops, never flagged.
static EMPTY_DEPENDS_ON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^\*Depends[-\s]?on:\s*\*\s*$").unwrap());
static NONE_DEPENDS_ON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^\*?\s*Depends[-\s]?on\s*:\s*none\b.*$").unwrap());

/// Shapes an attempted heading's text can take after `Task `, used to decide
/// whether a non-matching heading was *trying* to be a task heading.
static ID_WITH_SEPARATOR: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d+\s*[:\-—].*$").unwrap());
static WORD_ID_WITH_COLON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z]+\s*:.*$").unwrap());
static STARTS_WITH_DIGIT: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d.*$").unwrap());

/// One skipped or suspicious line: where it is, what it says, why it was
/// flagged. Port of Java's `PlanMarkdownParser.Diagnostic`.
#[derive(Debug, PartialEq, Eq)]
pub struct Diagnostic {
    pub line: u32,
    pub text: String,
    pub reason: String,
}

/// Parsed tasks plus the near-miss lines the strict grammar skipped, so a
/// mis-formatted plan can never fail silently. Port of `parseWithDiagnostics`.
pub fn parse_with_diagnostics(markdown: &str) -> (Vec<ParsedTask>, Vec<Diagnostic>) {
    let tasks = parse_tasks(markdown);
    let mut diagnostics = Vec::new();
    for (i, raw) in markdown.split('\n').enumerate() {
        let line = raw.trim_end();
        let line_no = i as u32 + 1;
        if let Some(valid) = HEADING_LINE.captures(line) {
            if let Some(d) = risk_tag_diagnostic(line_no, line, &valid) {
                diagnostics.push(d);
            }
            continue;
        }
        if let Some(d) = near_miss_diagnostic(line_no, line) {
            diagnostics.push(d);
        }
    }
    (tasks, diagnostics)
}

/// A grammatical heading whose name ends in an unrecognised `[tag]` — the
/// task parses, but the author probably meant a risk level.
fn risk_tag_diagnostic(line_no: u32, line: &str, valid: &regex::Captures) -> Option<Diagnostic> {
    if valid.get(3).is_some() {
        return None;
    }
    let tag = TRAILING_TAG.captures(valid[2].trim())?;
    Some(Diagnostic {
        line: line_no,
        text: line.to_string(),
        reason: format!("unrecognized risk tag [{}] — expected {GRAMMAR}", &tag[1]),
    })
}

fn near_miss_diagnostic(line_no: u32, line: &str) -> Option<Diagnostic> {
    let flag = |reason: String| {
        Some(Diagnostic { line: line_no, text: line.to_string(), reason })
    };

    if let Some(heading) = ANY_LEVEL_TASK_HEADING.captures(line) {
        let after_task = &heading[2];
        // Only call it an attempted heading on strong evidence: a separator
        // right after the id, a word id with a colon, or a trailing risk-style
        // tag. Plain prose headings ("## Task ordering note") stay unflagged.
        // (Dashes and em-dashes are treated alike — calibrated 2026-07-19.)
        let id_with_separator = ID_WITH_SEPARATOR.is_match(after_task);
        let word_id_with_colon = WORD_ID_WITH_COLON.is_match(after_task);
        let id_with_risk_tag =
            STARTS_WITH_DIGIT.is_match(after_task) && TRAILING_TAG.is_match(after_task);
        if !id_with_separator && !word_id_with_colon && !id_with_risk_tag {
            return None;
        }
        if &heading[1] != "###" {
            return flag(format!("task heading must be an h3: {GRAMMAR}"));
        }
        if word_id_with_colon && !STARTS_WITH_DIGIT.is_match(after_task) {
            return flag(format!("task id must be a number: {GRAMMAR}"));
        }
        return flag(format!("expected ':' after the task number: {GRAMMAR}"));
    }
    if BOLD_TASK_HEADING.is_match(line) {
        return flag(format!("task heading must be an h3 (### …), not bold text: {GRAMMAR}"));
    }
    if DEPENDS_ON_ATTEMPT.is_match(line)
        && !DEPENDS_ON_LINE.is_match(line)
        && !EMPTY_DEPENDS_ON.is_match(line)
        && !NONE_DEPENDS_ON.is_match(line)
    {
        return flag(
            "malformed depends-on line — expected *Depends-on: 1,2* \
             (first body line after its task heading)"
                .to_string(),
        );
    }
    None
}

pub fn parse_tasks(markdown: &str) -> Vec<ParsedTask> {
    let mut tasks = Vec::new();
    for caps in HEADING.captures_iter(markdown) {
        let heading_end = caps.get(0).unwrap().end();
        let region = task_region(markdown, heading_end);
        tasks.push(ParsedTask {
            // Java parses with Integer.parseInt and lets overflow throw
            // unchecked; the panic here is the same contract.
            id: caps[1].parse().expect("task id out of range"),
            name: caps[2].trim().to_owned(),
            risk: caps.get(3).map(|r| r.as_str().to_lowercase()).unwrap_or_default(),
            depends_on: depends_on_in(region),
        });
    }
    tasks
}

/// The slice after a heading in which its depends-on line may appear: capped
/// at [`REGION_CAP`] and at the next task heading, whichever comes first.
fn task_region(markdown: &str, heading_end: usize) -> &str {
    let mut cap = usize::min(heading_end + REGION_CAP, markdown.len());
    while !markdown.is_char_boundary(cap) {
        cap -= 1;
    }
    let capped = &markdown[heading_end..cap];
    match HEADING.find(capped) {
        Some(next) => &capped[..next.start()],
        None => capped,
    }
}

fn depends_on_in(region: &str) -> String {
    DEPENDS_ON
        .captures(region)
        .map(|c| c[1].chars().filter(|ch| !ch.is_whitespace()).collect())
        .unwrap_or_default()
}

// No dedicated Java unit test file exists for the parser; these pin the
// behaviours the Java regexes define and the fixture corpus exercises
// (fixtures/xml/01-fresh-init.xml is `plan init` over this same syntax).
/// Port of `PlanMarkdown.sliceTaskSection`: the `### Task {id}:` section of a
/// plan narrative, up to the next task heading, or `""` when absent.
///
/// Java reads the file here; the port takes the text so this module stays
/// pure, and [`crate::gw::TaskStore::slice_task_markdown`] owns the read.
/// The trailing colon in the marker is what stops task 1 from matching the
/// `### Task 10:` heading.
pub fn slice_task_section(markdown: &str, task_id: u32) -> String {
    let marker = format!("### Task {task_id}:");
    let Some(start) = markdown.find(&marker) else {
        return String::new();
    };
    let body = &markdown[start..];
    match body[marker.len()..].find("### Task ") {
        Some(next) => body[..marker.len() + next].trim().to_string(),
        None => body.trim().to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- parse_with_diagnostics (plan-109 Task 1 de-risk) ----
    // Spec: the plan file from Java's PlanInitDiagnosticsIntegrationTest,
    // where every heading is a different near-miss of the grammar.

    const EVERY_NEAR_MISS: &str = "\
# Plan — add a done command

## Tasks

## Task 1: Extend the task model [Low]

Depends-on: 1

### Task 2 - Show completion state in view [Low]

**Task 3: End-to-end test** [Low]
";

    #[test]
    fn every_near_miss_shape_is_diagnosed_with_its_own_reason() {
        let (tasks, diagnostics) = parse_with_diagnostics(EVERY_NEAR_MISS);

        assert!(tasks.is_empty(), "none of these headings is valid, got {tasks:?}");
        let reasons: Vec<(u32, &str)> =
            diagnostics.iter().map(|d| (d.line, d.reason.as_str())).collect();
        assert_eq!(
            reasons,
            vec![
                (5, "task heading must be an h3: ### Task N: Name [High|Medium|Low]"),
                (7, "malformed depends-on line — expected *Depends-on: 1,2* (first body line after its task heading)"),
                (9, "expected ':' after the task number: ### Task N: Name [High|Medium|Low]"),
                (11, "task heading must be an h3 (### …), not bold text: ### Task N: Name [High|Medium|Low]"),
            ]
        );
    }

    #[test]
    fn a_valid_heading_with_an_unrecognised_tag_is_flagged_but_still_parses() {
        let (tasks, diagnostics) = parse_with_diagnostics("### Task 1: Do it [Urgent]\n");

        assert_eq!(tasks.len(), 1, "the heading is grammatical; only its tag is odd");
        assert_eq!(tasks[0].name, "Do it [Urgent]", "an unknown tag stays in the name");
        assert_eq!(diagnostics.len(), 1);
        assert_eq!(
            diagnostics[0].reason,
            "unrecognized risk tag [Urgent] — expected ### Task N: Name [High|Medium|Low]"
        );
    }

    #[test]
    fn an_h3_heading_with_a_word_id_says_the_id_must_be_a_number() {
        // Distinct from the wrong-level and missing-colon reasons: the level
        // and the colon are both right, only the id is not numeric.
        let (tasks, diagnostics) = parse_with_diagnostics("### Task one: Do it [Low]\n");

        assert!(tasks.is_empty());
        assert_eq!(diagnostics.len(), 1);
        assert_eq!(
            diagnostics[0].reason,
            "task id must be a number: ### Task N: Name [High|Medium|Low]"
        );
    }

    #[test]
    fn a_multibyte_character_astride_the_region_cap_does_not_split_a_char() {
        // task_region truncates at heading end + 500 bytes, which can land
        // mid-character; slicing there would panic. Pad so an em-dash spans
        // the boundary.
        let padding = "x".repeat(REGION_CAP - 2);
        let markdown = format!("### Task 1: A [Low]\n{padding}—tail\n*Depends-on: 2*\n");

        let tasks = parse_tasks(&markdown);

        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0].depends_on, "", "the marker sits beyond the capped region");
    }

    #[test]
    fn deliberate_no_dependency_markers_and_prose_are_never_flagged() {
        // "*Depends-on:*" and "*Depends-on: none*" are historically widespread
        // no-ops; "## Task ordering note" is plain prose, not an attempt.
        let (_, diagnostics) = parse_with_diagnostics(
            "### Task 1: A [Low]\n*Depends-on:*\n*Depends-on: none yet*\n## Task ordering note\n",
        );

        assert!(diagnostics.is_empty(), "unexpected diagnostics: {diagnostics:?}");
    }

    // ---- slice_task_section ----

    const THREE_TASKS: &str = "\
## Tasks

### Task 1: Parse the input [High]

*Depends-on: none*

Body of one.

### Task 2: Write the output [Medium]

Body of two.

### Task 10: Wire it up [Low]

Body of ten.
";

    #[test]
    fn slices_from_the_heading_to_the_next_task_heading() {
        let section = slice_task_section(THREE_TASKS, 1);
        assert!(section.starts_with("### Task 1: Parse the input [High]"));
        assert!(section.ends_with("Body of one."), "trailing blank lines are trimmed: {section:?}");
        assert!(!section.contains("Task 2"));
    }

    #[test]
    fn slices_the_last_task_to_the_end_of_the_file() {
        let section = slice_task_section(THREE_TASKS, 10);
        assert!(section.starts_with("### Task 10: Wire it up [Low]"));
        assert!(section.ends_with("Body of ten."));
    }

    #[test]
    fn a_task_id_is_matched_whole_not_as_a_prefix() {
        // "### Task 1:" must not match the "### Task 10:" heading — the colon
        // is what anchors it.
        let only_ten = "### Task 10: Wire it up [Low]\n\nBody of ten.\n";
        assert_eq!(slice_task_section(only_ten, 1), "");
    }

    #[test]
    fn an_absent_task_slices_to_nothing() {
        assert_eq!(slice_task_section(THREE_TASKS, 99), "");
        assert_eq!(slice_task_section("", 1), "");
    }


    #[test]
    fn parses_heading_with_risk_and_lowercases_it() {
        let tasks = parse_tasks("### Task 1: Parse the input file [High]\n\nBody.\n");
        assert_eq!(
            tasks,
            vec![ParsedTask {
                id: 1,
                name: "Parse the input file".into(),
                risk: "high".into(),
                depends_on: String::new(),
            }]
        );
    }

    #[test]
    fn heading_without_risk_tag_yields_empty_risk() {
        let tasks = parse_tasks("### Task 4: Document the format\n");
        assert_eq!(tasks[0].risk, "");
        assert_eq!(tasks[0].name, "Document the format");
    }

    #[test]
    fn heading_match_is_case_insensitive() {
        let tasks = parse_tasks("### task 2: shout Quietly [low]\n");
        assert_eq!(tasks[0].id, 2);
        assert_eq!(tasks[0].risk, "low");
    }

    #[test]
    fn depends_on_strips_whitespace_between_ids() {
        let md = "### Task 3: Wire it [Low]\n\n*Depends-on: 1, 2*\n\nBody.\n";
        assert_eq!(parse_tasks(md)[0].depends_on, "1,2");
    }

    // Regression for the plan-102 authoring bug: `*Depends-on:* 1` (closing
    // asterisk after the colon) must NOT match — only `*Depends-on: 1*` does.
    #[test]
    fn wrongly_italicized_depends_on_is_ignored() {
        let md = "### Task 2: Write it [Medium]\n\n*Depends-on:* 1\n";
        assert_eq!(parse_tasks(md)[0].depends_on, "");
    }

    // PB-352: the search region is capped ~500 chars after the heading; a
    // depends-on line beyond it is silently dropped.
    #[test]
    fn depends_on_beyond_the_500_char_window_is_dropped() {
        let md = format!(
            "### Task 2: Long body [Low]\n\n{}\n*Depends-on: 1*\n",
            "x".repeat(600)
        );
        assert_eq!(parse_tasks(&md)[0].depends_on, "");
    }

    #[test]
    fn depends_on_belongs_to_its_own_task_region() {
        let md = "### Task 1: First [High]\n\nBody.\n\n### Task 2: Second [Low]\n\n*Depends-on: 1*\n";
        let tasks = parse_tasks(md);
        assert_eq!(tasks[0].depends_on, "");
        assert_eq!(tasks[1].depends_on, "1");
    }
}
