//! Byte-exact emitter for JAXB's formatted output — the single source of
//! truth for the layout rules the golden corpus pins (fixtures/README.md):
//! `standalone="yes"` declaration, 4-space indent, string leaves always
//! paired (`<commit></commit>`), empty containers self-closed (`<comments/>`),
//! `& < >` escaped in text (quotes not), `& < > "` escaped in attributes,
//! unknown elements re-indented into the pretty layout, trailing newline.

use super::raw::{RawElement, RawNode};

const INDENT: &str = "    ";

pub(crate) struct JaxbLayout {
    out: String,
}

impl JaxbLayout {
    pub(crate) fn new() -> Self {
        let mut out = String::with_capacity(4096);
        out.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        JaxbLayout { out }
    }

    pub(crate) fn into_string(self) -> String {
        self.out
    }

    pub(crate) fn open(&mut self, level: usize, name: &str) {
        self.indent(level);
        self.out.push('<');
        self.out.push_str(name);
        self.out.push_str(">\n");
    }

    pub(crate) fn close(&mut self, level: usize, name: &str) {
        self.indent(level);
        self.out.push_str("</");
        self.out.push_str(name);
        self.out.push_str(">\n");
    }

    /// String-typed leaf: always paired tags, even when empty.
    pub(crate) fn leaf(&mut self, level: usize, name: &str, text: &str) {
        self.indent(level);
        self.out.push('<');
        self.out.push_str(name);
        self.out.push('>');
        self.out.push_str(&esc_text(text));
        self.out.push_str("</");
        self.out.push_str(name);
        self.out.push_str(">\n");
    }

    /// Empty container: self-closing form.
    pub(crate) fn empty(&mut self, level: usize, name: &str) {
        self.indent(level);
        self.out.push('<');
        self.out.push_str(name);
        self.out.push_str("/>\n");
    }

    /// Preserved `xs:any` element, re-emitted the way JAXB re-emits foreign
    /// elements (fixtures 06 → 07): text-only content inline, element
    /// children on their own lines, no children self-closed.
    pub(crate) fn raw(&mut self, level: usize, el: &RawElement) {
        self.indent(level);
        self.out.push('<');
        self.out.push_str(&el.name);
        for (k, v) in &el.attrs {
            self.out.push(' ');
            self.out.push_str(k);
            self.out.push_str("=\"");
            self.out.push_str(&esc_attr(v));
            self.out.push('"');
        }
        if el.children.is_empty() {
            self.out.push_str("/>\n");
        } else if !el.has_element_children() {
            self.out.push('>');
            self.out.push_str(&esc_text(&el.text()));
            self.out.push_str("</");
            self.out.push_str(&el.name);
            self.out.push_str(">\n");
        } else {
            self.out.push_str(">\n");
            self.raw_children(level + 1, el);
            self.close(level, &el.name);
        }
    }

    fn raw_children(&mut self, level: usize, el: &RawElement) {
        for child in &el.children {
            match child {
                RawNode::Element(e) => self.raw(level, e),
                RawNode::Text(t) => {
                    self.indent(level);
                    self.out.push_str(&esc_text(t));
                    self.out.push('\n');
                }
            }
        }
    }

    fn indent(&mut self, level: usize) {
        for _ in 0..level {
            self.out.push_str(INDENT);
        }
    }
}

fn esc_text(t: &str) -> String {
    t.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

fn esc_attr(t: &str) -> String {
    esc_text(t).replace('"', "&quot;")
}
