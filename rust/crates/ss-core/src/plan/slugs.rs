//! Port of `Slugs`: free-text description → branch-safe slug and the
//! `t/{prefix}-{slug}` task-branch name. Folds accented Latin to ASCII
//! (NFD + strip combining marks) before the lowercase / non-alphanumeric-to-
//! hyphen / trim transform, so "Café déjà" slugs to `cafe-deja` rather than
//! the lossy `caf-d-j`. No external slug crate — these are short
//! dev-authored phrases.

use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;

pub fn slugify(text: &str) -> String {
    let folded: String = text.nfd().filter(|c| !is_combining_mark(*c)).collect();
    let lower = folded.to_lowercase();

    // [^a-z0-9]+ → single hyphen, then trim leading/trailing hyphens.
    let mut slug = String::with_capacity(lower.len());
    let mut hyphen_pending = false;
    for ch in lower.chars() {
        if ch.is_ascii_lowercase() || ch.is_ascii_digit() {
            if hyphen_pending && !slug.is_empty() {
                slug.push('-');
            }
            hyphen_pending = false;
            slug.push(ch);
        } else {
            hyphen_pending = true;
        }
    }
    slug
}

/// The `t/{prefix}-{slug}` task-branch name, omitting the trailing hyphen
/// when the description slugs to nothing.
pub fn branch_name(prefix: &str, desc: &str) -> String {
    let slug = slugify(desc);
    if slug.is_empty() {
        format!("t/{prefix}")
    } else {
        format!("t/{prefix}-{slug}")
    }
}

// Tests ported verbatim from SlugsTest.java.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lowercases_and_hyphenates_plain_phrase() {
        assert_eq!(slugify("Desktop UI"), "desktop-ui");
    }

    #[test]
    fn folds_accented_latin_to_ascii() {
        assert_eq!(slugify("Café déjà vu"), "cafe-deja-vu");
    }

    #[test]
    fn collapses_runs_of_punctuation_to_single_hyphen() {
        assert_eq!(slugify("Fix:  the   Bug!!!"), "fix-the-bug");
    }

    #[test]
    fn trims_leading_and_trailing_hyphens() {
        assert_eq!(slugify("  --middle--  "), "middle");
    }

    #[test]
    fn all_punctuation_slugs_to_empty() {
        assert_eq!(slugify("!!! @#$ ..."), "");
    }

    #[test]
    fn branch_name_joins_prefix_and_slug() {
        assert_eq!(branch_name("1", "Desktop UI"), "t/1-desktop-ui");
    }

    #[test]
    fn branch_name_omits_trailing_hyphen_when_slug_empty() {
        assert_eq!(branch_name("3", "!!!"), "t/3");
    }

    #[test]
    fn branch_name_works_with_issue_id_prefix() {
        assert_eq!(branch_name("pb-310", "my feature"), "t/pb-310-my-feature");
    }
}
