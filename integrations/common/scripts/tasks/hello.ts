import { XMLParser } from 'fast-xml-parser';

const parser = new XMLParser();
const result = parser.parse('<hello>world</hello>');
console.log('shipsmooth task scripts: deps OK, fast-xml-parser loaded successfully');
console.log('Parsed XML:', JSON.stringify(result));
