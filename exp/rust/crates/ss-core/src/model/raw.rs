//! Generic XML element tree: the parse target for whole documents and the
//! preserved form of `xs:any` extension elements (`<depends-on>`, unknown
//! future fields). Internal collaborator of [`super::PlanTasks`]; the types
//! are public only because they appear in the model's extension fields.

use quick_xml::events::Event;
use quick_xml::Reader;

use crate::{Error, Result};

#[derive(Clone, Debug)]
pub struct RawElement {
    pub name: String,
    pub attrs: Vec<(String, String)>,
    pub children: Vec<RawNode>,
}

#[derive(Clone, Debug)]
pub enum RawNode {
    Element(RawElement),
    Text(String),
}

impl RawElement {
    /// Concatenated text content (empty string for an element with none).
    pub fn text(&self) -> String {
        let mut s = String::new();
        for child in &self.children {
            if let RawNode::Text(t) = child {
                s.push_str(t);
            }
        }
        s
    }

    pub(crate) fn elements(&self) -> impl Iterator<Item = &RawElement> {
        self.children.iter().filter_map(|c| match c {
            RawNode::Element(e) => Some(e),
            RawNode::Text(_) => None,
        })
    }

    pub(crate) fn child_text(&self, name: &str) -> String {
        self.elements().find(|c| c.name == name).map(|c| c.text()).unwrap_or_default()
    }

    pub(crate) fn has_element_children(&self) -> bool {
        self.children.iter().any(|c| matches!(c, RawNode::Element(_)))
    }
}

/// Parses a whole document to its root element. Decl, comments, PIs and
/// doctype are skipped; whitespace-only text is treated as pretty-print
/// formatting, not content.
pub(crate) fn parse_document(xml: &str) -> Result<RawElement> {
    let mut reader = Reader::from_str(xml);
    loop {
        match reader.read_event().map_err(xml_err)? {
            Event::Decl(_) | Event::Comment(_) | Event::PI(_) | Event::DocType(_) => {}
            Event::Text(t) => {
                if !t.unescape().map_err(xml_err)?.trim().is_empty() {
                    return Err(Error::Xml("unexpected text before root element".into()));
                }
            }
            Event::Start(s) => {
                let parts = start_parts(&s)?;
                return parse_element(&mut reader, parts);
            }
            Event::Empty(s) => {
                let (name, attrs) = start_parts(&s)?;
                return Ok(RawElement { name, attrs, children: Vec::new() });
            }
            Event::Eof => return Err(Error::Xml("empty document".into())),
            _ => {}
        }
    }
}

fn parse_element(
    reader: &mut Reader<&[u8]>,
    (name, attrs): (String, Vec<(String, String)>),
) -> Result<RawElement> {
    let mut children: Vec<RawNode> = Vec::new();
    loop {
        match reader.read_event().map_err(xml_err)? {
            Event::Start(s) => {
                let parts = start_parts(&s)?;
                children.push(RawNode::Element(parse_element(reader, parts)?));
            }
            Event::Empty(s) => {
                let (n, a) = start_parts(&s)?;
                children.push(RawNode::Element(RawElement { name: n, attrs: a, children: Vec::new() }));
            }
            Event::Text(t) => {
                let txt = t.unescape().map_err(xml_err)?;
                if !txt.trim().is_empty() {
                    push_text(&mut children, &txt);
                }
            }
            Event::CData(c) => {
                push_text(&mut children, &String::from_utf8_lossy(c.as_ref()));
            }
            Event::End(_) => return Ok(RawElement { name, attrs, children }),
            Event::Eof => return Err(Error::Xml(format!("unclosed element <{name}>"))),
            _ => {}
        }
    }
}

/// Adjacent text runs (e.g. around a CDATA section) merge into one node.
fn push_text(children: &mut Vec<RawNode>, txt: &str) {
    if let Some(RawNode::Text(last)) = children.last_mut() {
        last.push_str(txt);
    } else {
        children.push(RawNode::Text(txt.to_owned()));
    }
}

fn start_parts(start: &quick_xml::events::BytesStart<'_>) -> Result<(String, Vec<(String, String)>)> {
    let name = String::from_utf8_lossy(start.name().as_ref()).into_owned();
    let mut attrs = Vec::new();
    for att in start.attributes() {
        let att = att.map_err(|e| Error::Xml(e.to_string()))?;
        attrs.push((
            String::from_utf8_lossy(att.key.as_ref()).into_owned(),
            att.unescape_value().map_err(xml_err)?.into_owned(),
        ));
    }
    Ok((name, attrs))
}

fn xml_err(e: quick_xml::Error) -> Error {
    Error::Xml(e.to_string())
}
