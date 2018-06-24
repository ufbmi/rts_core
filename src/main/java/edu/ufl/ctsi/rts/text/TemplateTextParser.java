package edu.ufl.ctsi.rts.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import edu.uams.dbmi.rts.cui.Cui;
import edu.uams.dbmi.rts.iui.Iui;
import edu.uams.dbmi.rts.metadata.RtsChangeReason;
import edu.uams.dbmi.rts.metadata.RtsChangeType;
import edu.uams.dbmi.rts.metadata.RtsErrorCode;
import edu.uams.dbmi.rts.template.ATemplate;
import edu.uams.dbmi.rts.template.MetadataTemplate;
import edu.uams.dbmi.rts.template.PtoCTemplate;
import edu.uams.dbmi.rts.template.PtoDETemplate;
import edu.uams.dbmi.rts.template.PtoLackUTemplate;
import edu.uams.dbmi.rts.template.PtoPTemplate;
import edu.uams.dbmi.rts.template.PtoUTemplate;
import edu.uams.dbmi.rts.template.RtsTemplate;
import edu.uams.dbmi.rts.time.TemporalReference;
import edu.uams.dbmi.rts.time.TemporalRegion;
import edu.uams.dbmi.rts.uui.Uui;
import edu.uams.dbmi.util.iso8601.Iso8601DateParseException;
import edu.uams.dbmi.util.iso8601.Iso8601DateTimeParser;
import edu.uams.dbmi.util.iso8601.Iso8601TimeParseException;


public class TemplateTextParser {
	
	protected BufferedReader r;
	
	HashSet<RtsTemplate> templates;
	HashSet<TemporalRegion> temporalRegions;
	
	protected Iso8601DateTimeParser dt_parser;
	
	public TemplateTextParser(BufferedReader r) {
		this.r = r;
		templates = new HashSet<RtsTemplate>();
		temporalRegions = new HashSet<TemporalRegion>();
		
		dt_parser = new Iso8601DateTimeParser();
	}
	
	public void parseTemplates() throws IOException, ParseException {
		
		/* Here, we cannot use the generic parsing method because we are only
		 * pulling in text in 64 character chunks at a time.
		 */
			int readSize = 8192;
			char[] read = new char[readSize];
				
			int i = 1;
			char prior_read = '\0';
			StringBuilder sb = new StringBuilder();
			boolean inside_quote = false;
			int position = 0;
			while (i > 0) {
				i = r.read(read, 0, 64);
				//System.out.println(read);
				for (int j=0; j<i; j++) {
					/*
					 * If we get an opening quote character, and it is not escaped, then we have
					 * 	to open a quote, or if we're already in a quote, signal an error
					 */
					if (read[j] == TemplateTextWriter.QUOTE_OPEN && prior_read != TemplateTextWriter.ESCAPE) {
						if (!inside_quote) {
							inside_quote = true;
							sb.append(read[j]);
						} else {
							//we can't have an open quote that is unescaped
							throw new ParseException("Cannot open quoted text within quoted text.", position);
						}
					}
					/*
					 * If we get a closing quote character, and it is not escaped, then we have
					 *   to close the quote, or if we're not in a quote, signal an error.
					 */
					else if (read[j] == TemplateTextWriter.QUOTE_CLOSE 
								&& prior_read != TemplateTextWriter.ESCAPE) {
						if (inside_quote) {
							inside_quote = false;
							sb.append(read[j]);
						} else {
							throw new ParseException("Cannot close quoted text without corresponding open quote.", position);
						}
					} 
					/*
					 * A TEMPLATE_DELIM character terminates a template iff we are not 
					 * 	inside a quote and it is not escaped.
					 */
					else if (read[j] == TemplateTextWriter.TEMPLATE_DELIM && prior_read != '\\' && !inside_quote) {
						String template = sb.toString();
						parseTemplateFromText(template);
						System.out.println("Template = \"" + sb.toString() + "\"");
						sb = new StringBuilder();
						prior_read = '\0';
					} else {
						sb.append(read[j]);
						prior_read = read[j];
					}
					position++;
				}
			}
	}
	
	protected void parseTemplateFromText(String templateText) throws ParseException {
		
		List<String> blocks = splitDelimitedQuotedAndEscapedText(templateText, 
				TemplateTextWriter.BLOCK_DELIM,
				TemplateTextWriter.QUOTE_OPEN,
				TemplateTextWriter.QUOTE_CLOSE,
				TemplateTextWriter.ESCAPE);
	
		if (blocks.size() != 2) System.err.println("INCORRECT NUMBER OF BLOCKS!!! " + blocks.size());
		
		String templateBlock = blocks.get(0);
		String contentBlock = blocks.get(1);
		
		List<String> templateFields = splitDelimitedQuotedAndEscapedText(templateBlock, 
				TemplateTextWriter.FIELD_DELIM,
				TemplateTextWriter.QUOTE_OPEN,
				TemplateTextWriter.QUOTE_CLOSE,
				TemplateTextWriter.ESCAPE);
		
		List<String> contentFields = splitDelimitedQuotedAndEscapedText(contentBlock, 
				TemplateTextWriter.FIELD_DELIM,
				TemplateTextWriter.QUOTE_OPEN,
				TemplateTextWriter.QUOTE_CLOSE,
				TemplateTextWriter.ESCAPE);
		
		String tupleType = templateFields.get(0);
		RtsTemplate template = null;
		TemporalRegion temporalRegion = null;
		if (tupleType.equals("A")) {
			template = new ATemplate();		
		} else if (tupleType.equals("U")) {
			template = new PtoUTemplate();
		} else if (tupleType.equals("P")) {
			template = new PtoPTemplate();
		} else if (tupleType.equals("L")) {
			template = new PtoLackUTemplate();
		} else if (tupleType.equals("C")) {
			template = new PtoCTemplate();
		} else if (tupleType.equals("E")) {
			template = new PtoDETemplate();
		} else if (tupleType.equals("D")) {
			template = new MetadataTemplate();
		} else if (tupleType.equals("T")) {
			temporalRegion = createTemporalRegion(contentFields);
		} else {
			throw new RuntimeException("Unrecognized tuple type: " + tupleType);
		}
		
		if (template != null) {
			template.setTemplateIui(Iui.createFromString(templateFields.get(1)));
			template.setAuthorIui(Iui.createFromString(contentFields.get(0)));
			populateTemplate(template, contentFields);
			templates.add(template);
		}
		
		if (temporalRegion != null) {
			temporalRegions.add(temporalRegion);
		}
	}

	protected List<String> splitDelimitedQuotedAndEscapedText(String text, char delim, char openQuote, 
			char closeQuote, char escape) throws ParseException {
		ArrayList<String> fragments = new ArrayList<String>(text.length()/2);
		/*
		 * Split on non-escaped template block delimiter and the delimiter
		 * 	cannot be inside quotes either.
		 */
		char[] textChars = text.toCharArray();
		
		boolean inside_quote = false;
		char lastChar = '\0';
		int begin = 0;
		for (int i=0; i<textChars.length; i++) {
			char c = textChars[i];

			if (c == delim && lastChar != escape && !inside_quote) {
				String nextFragment = text.substring(begin,i);
				if (nextFragment.indexOf(openQuote) == 0 && 
						nextFragment.indexOf(closeQuote) == nextFragment.length()-1) 
					nextFragment = nextFragment.substring(1, nextFragment.length()-1);
				fragments.add(nextFragment);  //gets every character up to ith character, but not including it
				begin = i+1;		//skip ith character because it's a delimiter		
			} else if (c == openQuote && lastChar != escape) {
				if (!inside_quote) {
					inside_quote = true;
				} else {
					throw new ParseException("Already inside quote. Cannot open quote." + text, i);
				}
			} else if (c == closeQuote && lastChar != escape) {
				if (inside_quote) {
					inside_quote = false;
				} else {
					throw new ParseException("Not inside quote. Cannot close quote." + text, i);
				}
			}
			lastChar = c;
		}
		fragments.add(text.substring(begin));  //the last delimited piece is hanging out here to be collected
		return fragments;
	}
	
	protected void populateTemplate(RtsTemplate t, List<String> contentFields) {
		if (t instanceof ATemplate) {
			populateATemplate((ATemplate)t, contentFields);
		} else if (t instanceof PtoUTemplate) {
			populatePtoUTemplate((PtoUTemplate)t, contentFields);
		} else if (t instanceof PtoPTemplate) {
			populatePtoPTemplate((PtoPTemplate)t, contentFields);
		} else if (t instanceof PtoLackUTemplate) {
			populatePtoLackUTemplate((PtoLackUTemplate)t, contentFields);
		} else if (t instanceof PtoCTemplate) {
			populatePtoCTemplate((PtoCTemplate)t, contentFields);
		} else if (t instanceof PtoDETemplate) {
			populatePtoDETemplate((PtoDETemplate)t, contentFields);
		} else if (t instanceof MetadataTemplate) {
			populateMetadataTemplate((MetadataTemplate)t, contentFields);
		}
	}

	private void populateATemplate(ATemplate t, List<String> contentFields) {
		// set the referent IUI - IUIp
		t.setReferentIui(Iui.createFromString(contentFields.get(2)));
		try {
			//set the authoring timestamp - ta
			t.setAuthoringTimestamp(dt_parser.parse(contentFields.get(1)));
		} catch (Iso8601DateParseException e) {
			e.printStackTrace();
		} catch (Iso8601TimeParseException e) {
			e.printStackTrace();
		}
	}

	private void populatePtoUTemplate(PtoUTemplate t, List<String> contentFields) {
		
		//contentFields.get(1) is ta
		TemporalReference tr = new TemporalReference(contentFields.get(1), contentFields.get(1).contains("Z"));
		t.setAuthoringTimeReference(tr);
		
		//contentFields.get(2) is r
		t.setRelationshipURI(URI.create(contentFields.get(2)));
		
		//contentFields.get(3) is IUIo for r
		t.setRelationshipOntologyIui(Iui.createFromString(contentFields.get(3)));
		
		//contentFields.get(4) is IUIp
		t.setReferentIui(Iui.createFromString(contentFields.get(4)));
		
		//contentFields.get(5) is UUI 
		t.setUniversalUui(new Uui(contentFields.get(5)));
		
		//contentFields.get(6) is IUIo for UUI
		t.setUniversalOntologyIui(Iui.createFromString(contentFields.get(6)));
		
		//contentFields.get(7) is tr
		t.setTemporalReference(new TemporalReference(contentFields.get(7), contentFields.get(7).contains("Z")));

	}

	private void populatePtoPTemplate(PtoPTemplate t, List<String> contentFields) {
		
		//contentFields.get(1) is ta
		TemporalReference tr = new TemporalReference(contentFields.get(1), contentFields.get(1).contains("Z"));
		t.setAuthoringTimeReference(tr);
		
		//contentFields.get(2) is r
		t.setRelationshipURI(URI.create(contentFields.get(2)));
		
		//contentFields.get(3) is IUIo for r
		t.setRelationshipOntologyIui(Iui.createFromString(contentFields.get(3)));
		
		//contentFields.get(4) is P
		String[] prefs = contentFields.get(4).split(Pattern.quote(""+TemplateTextWriter.SUBFIELD_DELIM));
		for (String pref : prefs) {
			String[] refInfo = pref.split(Pattern.quote("="));
			if (refInfo[0].equals("iui")) {
				Iui iui = Iui.createFromString(refInfo[1]);
				t.addParticular(iui);
			} else if (refInfo[0].equals("tref")) {
				TemporalReference tref = new TemporalReference(refInfo[1], refInfo[1].contains("Z"));
				t.addParticular(tref);
			} else {
				throw new IllegalArgumentException("particular reference type must be 'iui' or 'tref'");
			}
		}
					
		//contentFields.get(5) is tr
		t.setTemporalReference(new TemporalReference(contentFields.get(5), contentFields.get(5).contains("Z")));

	}

	private void populatePtoLackUTemplate(PtoLackUTemplate t, List<String> contentFields) {
		
		//contentFields.get(1) is ta
		TemporalReference tr = new TemporalReference(contentFields.get(1), contentFields.get(1).contains("Z"));
		t.setAuthoringTimeReference(tr);
		
		//contentFields.get(2) is r
		t.setRelationshipURI(URI.create(contentFields.get(2)));
		
		//contentFields.get(3) is IUIo for r
		t.setRelationshipOntologyIui(Iui.createFromString(contentFields.get(3)));
		
		//contentFields.get(4) is IUIp
		t.setReferentIui(Iui.createFromString(contentFields.get(4)));
		
		//contentFields.get(5) is UUI 
		t.setUniversalUui(new Uui(contentFields.get(5)));
		
		//contentFields.get(6) is IUIo for UUI
		t.setUniversalOntologyIui(Iui.createFromString(contentFields.get(6)));
		
		//contentFields.get(7) is tr
		t.setTemporalReference(new TemporalReference(contentFields.get(7), contentFields.get(7).contains("Z")));
		
	}

	private void populatePtoCTemplate(PtoCTemplate t, List<String> contentFields) {
		
		//contentFields.get(1) is ta
		TemporalReference tr = new TemporalReference(contentFields.get(1), contentFields.get(1).contains("Z"));
		t.setAuthoringTimeReference(tr);
		
		//contentFields.get(2) is IUIc
		t.setConceptSystemIui(Iui.createFromString(contentFields.get(2)));
		
		//contentFields.get(3) is IUIp
		t.setReferentIui(Iui.createFromString(contentFields.get(3)));
		
		//contentFields.get(4) is UUI 
		t.setConceptCui(new Cui(contentFields.get(4)));
		
		//contentFields.get(5) is tr
		t.setTemporalReference(new TemporalReference(contentFields.get(5), contentFields.get(5).contains("Z")));
	}

	private void populatePtoDETemplate(PtoDETemplate t, List<String> contentFields) {
		
		//contentFields.get(1) is ta
		TemporalReference tr = new TemporalReference(contentFields.get(1), contentFields.get(1).contains("Z"));
		t.setAuthoringTimeReference(tr);
		
		//contentFields.get(2) is r
		t.setRelationshipURI(URI.create(contentFields.get(2)));
		
		//contentFields.get(3) is IUIo for r
		t.setRelationshipOntologyIui(Iui.createFromString(contentFields.get(3)));
		
		//contentFields.get(4) is IUIp
		String[] refInfo = contentFields.get(4).split(Pattern.quote("="));
		if (refInfo[0].equals("iui")) {
			Iui iui = Iui.createFromString(refInfo[1]);
			t.setReferent(iui);
		} else if (refInfo[0].equals("tref")) {
			TemporalReference tref = new TemporalReference(refInfo[1], refInfo[1].contains("Z"));
			t.setReferent(tref);
		} else {
			throw new IllegalArgumentException("particular reference type must be 'iui' or 'tref'");
		}
		
		//contentFields.get(5) is datatype UUI 
		t.setDatatypeUui(new Uui(contentFields.get(5)));
		
		//contentFields.get(6) is IUIo for datatype UUI
		t.setDatatypeOntologyIui(Iui.createFromString(contentFields.get(6)));
		
		//contentFields.get(7) is naming system IUI
		t.setNamingSystem(Iui.createFromString(contentFields.get(7)));
		
		//contentFields.get(8) is data
		t.setData(contentFields.get(8).getBytes());
		
	}

	private void populateMetadataTemplate(MetadataTemplate t, List<String> contentFields) {
		// TODO Auto-generated method stub
		
		//contentFields.get(1) is td
		try {
			t.setAuthoringTimestamp(dt_parser.parse(contentFields.get(1)));
		} catch (Iso8601DateParseException | Iso8601TimeParseException e) {
			e.printStackTrace();
		}
		
		//contentFields.get(2) is template IUI
		t.setReferent(Iui.createFromString(contentFields.get(2)));
		
		//contentFields.get(3) is change type
		RtsChangeType ct = RtsChangeType.valueOf(contentFields.get(3));
		t.setChangeType(ct);
		
		//contentFields.get(4) is change reason
		RtsChangeReason cr = RtsChangeReason.valueOf(contentFields.get(4));
		t.setChangeReason(cr);
		
		//contentFields.get(5) is error code
		RtsErrorCode ec = RtsErrorCode.valueOf(contentFields.get(5));
		t.setErrorCode(ec);
		
		//contentFields.get(6) is list of replacement templates
		String replTemplateField = contentFields.get(6);
		if (replTemplateField.length() > 0) {
			String[] replTemplateIuis = replTemplateField.split(Pattern.quote(""+TemplateTextWriter.SUBFIELD_DELIM));
			HashSet<Iui> replIuis = new HashSet<Iui>();
			for (String replTemplateIui : replTemplateIuis) {
				Iui replIui = Iui.createFromString(replTemplateIui);
				replIuis.add(replIui);
			}
			if (replIuis.size() > 0) t.setReplacementTemplateIuis(replIuis);
		}
	}
	
	private TemporalRegion createTemporalRegion(List<String> contentFields) {
		/* 2 - last thing in content fields is IUI of naming system
		 * 	This must be: 
		 *     1. D4AF5C9A-47BA-4BF4-9BAE-F13A8ED6455E if it's an ISO8601 date/time, and possibly even 
		 *     		any Gregorian Calendar system.
		 *     2. DB2282A4-631F-4D2C-940F-A220C496F6BE if it's a generic temporal reference that refers to 
		 *     	    some time interval either whose boundaries are not known or that spans but does not 
		 *          equate to intervals named in ISO8601 or the Gregorian more generally.
		 *     3. Some other IUI that refers to some calendaring or other naming system for temporal
		 *          regions.  Could be Hebrew calendar, Julian calendar, for example.
		 */
		String nsIuiTxt = contentFields.get(2);
		Iui nsIui = Iui.createFromString(nsIuiTxt);	
		
		// 0 - first thing in content fields is the temporal reference for the region
		String tRefTxt = contentFields.get(0);
		TemporalReference tref = new TemporalReference(tRefTxt, nsIui.equals(TemporalRegion.ISO_IUI));
		
		// 1 - next thing in content fields is UUI -- almost always will BFO IRI -- for type of region
		String typeTxt = contentFields.get(1);
		Uui typeUui = new Uui(typeTxt);
				
		return new TemporalRegion(tref, typeUui, nsIui);
	}
}
