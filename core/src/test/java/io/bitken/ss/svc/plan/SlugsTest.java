package io.bitken.ss.svc.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SlugsTest {

    @Test
    void lowercasesAndHyphenatesPlainPhrase() {
        assertEquals("desktop-ui", Slugs.slugify("Desktop UI"));
    }

    @Test
    void foldsAccentedLatinToAscii() {
        assertEquals("cafe-deja-vu", Slugs.slugify("Café déjà vu"));
    }

    @Test
    void collapsesRunsOfPunctuationToSingleHyphen() {
        assertEquals("fix-the-bug", Slugs.slugify("Fix:  the   Bug!!!"));
    }

    @Test
    void trimsLeadingAndTrailingHyphens() {
        assertEquals("middle", Slugs.slugify("  --middle--  "));
    }

    @Test
    void allPunctuationSlugsToEmpty() {
        assertEquals("", Slugs.slugify("!!! @#$ ..."));
    }

    @Test
    void branchNameJoinsPrefixAndSlug() {
        assertEquals("t/1-desktop-ui", Slugs.branchName("1", "Desktop UI"));
    }

    @Test
    void branchNameOmitsTrailingHyphenWhenSlugEmpty() {
        assertEquals("t/3", Slugs.branchName("3", "!!!"));
    }

    @Test
    void branchNameWorksWithIssueIdPrefix() {
        assertEquals("t/pb-310-my-feature", Slugs.branchName("pb-310", "my feature"));
    }
}
