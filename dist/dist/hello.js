"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const fast_xml_parser_1 = require("fast-xml-parser");
const parser = new fast_xml_parser_1.XMLParser();
const result = parser.parse('<hello>world</hello>');
console.log('shipsmooth task scripts: deps OK, fast-xml-parser loaded successfully');
console.log('Parsed XML:', JSON.stringify(result));
