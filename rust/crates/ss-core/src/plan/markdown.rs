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

pub fn parse_tasks(_markdown: &str) -> Vec<ParsedTask> {
    todo!("plan-102 Task 4")
}

// No dedicated Java unit test file exists for the parser; these pin the
// behaviours the Java regexes define and the fixture corpus exercises
// (rust/fixtures/xml/01-fresh-init.xml is `plan init` over this same syntax).
#[cfg(test)]
mod tests {
    use super::*;

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
