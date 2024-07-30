
/** **********************************************************************
 *
 * Copyright 2019 Jochen Staerk
 *
 * Use is subject to license terms.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *********************************************************************** */
package org.mustangproject.ZUGFeRD;

import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VisualizationTest extends ResourceCase {

	public void testCIIVisualizationBasic() {

		// the writing part
		String sourceFilename = "factur-x.xml";
		File CIIinputFile = getResourceAsFile(sourceFilename);

		String expected = null;
		String result = null;
		try {
			ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
			/* remove file endings so that tests can also pass after checking
			   out from git with arbitrary options (which may include CSRF changes)
			 */
			result = zvi.visualize(CIIinputFile.getAbsolutePath(),ZUGFeRDVisualizer.Language.FR).replace("\r","").replace("\n","");

			File expectedResult=getResourceAsFile("factur-x-vis.fr.html");
			expected = new String(Files.readAllBytes(expectedResult.toPath()), StandardCharsets.UTF_8).replace("\r","").replace("\n","");
			// remove linebreaks as well...

		} catch (UnsupportedOperationException e) {
			fail("UnsupportedOperationException should not happen: "+e.getMessage());
		} catch (IllegalArgumentException e) {
			fail("IllegalArgumentException should not happen: "+e.getMessage());
		} catch (TransformerException e) {
			fail("TransformerException should not happen: "+e.getMessage());
		} catch (IOException e) {
			fail("IOException should not happen: "+e.getMessage());
		} catch (ParserConfigurationException e) {
			fail("ParserConfigurationException should not happen: "+e.getMessage());
		} catch (SAXException e) {
			fail("SAXException should not happen: "+e.getMessage());
		}


		assertNotNull(result);
		// Reading ZUGFeRD
		assertEquals(expected, result);
	}
	public void testUBLCreditNoteVisualizationBasic() {

		// the writing part
		File UBLinputFile = getResourceAsFile("ubl-creditnote.xml");

		String expected = null;
		String result = null;
		try {
			ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
			/* remove file endings so that tests can also pass after checking
			   out from git with arbitrary options (which may include CSRF changes)
			 */
			result = zvi.visualize(UBLinputFile.getAbsolutePath(),ZUGFeRDVisualizer.Language.EN).replace("\r","").replace("\n","").replace(" ","").replace("\t","");

			File expectedResult=getResourceAsFile("factur-x-vis-ubl-creditnote.en.html");
			expected = new String(Files.readAllBytes(expectedResult.toPath()), StandardCharsets.UTF_8).replace("\r","").replace("\n","").replace(" ","").replace("\t","");
			// remove linebreaks as well...

		} catch (UnsupportedOperationException e) {
			fail("UnsupportedOperationException should not happen: "+e.getMessage());
		} catch (IllegalArgumentException e) {
			fail("IllegalArgumentException should not happen: "+e.getMessage());
		} catch (TransformerException e) {
			fail("TransformerException should not happen: "+e.getMessage());
		} catch (IOException e) {
			fail("IOException should not happen: "+e.getMessage());
		} catch (ParserConfigurationException e) {
			fail("ParserConfigurationException should not happen: "+e.getMessage());
		} catch (SAXException e) {
			fail("SAXException should not happen: "+e.getMessage());
		}


		assertNotNull(result);
		// Reading ZUGFeRD
		assertEquals(expected, result);
	}
	public void testUBLVisualizationBasic() {

		// the writing part
		File UBLinputFile = getResourceAsFile("ubl/01.01a-INVOICE.ubl.xml");

		String expected = null;
		String result = null;
		try {
			ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
			/* remove file endings so that tests can also pass after checking
			   out from git with arbitrary options (which may include CSRF changes)
			 */
			result = zvi.visualize(UBLinputFile.getAbsolutePath(),ZUGFeRDVisualizer.Language.EN).replace("\r","").replace("\n","");

			File expectedResult=getResourceAsFile("factur-x-vis-ubl.en.html");
			expected = new String(Files.readAllBytes(expectedResult.toPath()), StandardCharsets.UTF_8).replace("\r","").replace("\n","");
			// remove linebreaks as well...

		} catch (UnsupportedOperationException e) {
			fail("UnsupportedOperationException should not happen: "+e.getMessage());
		} catch (IllegalArgumentException e) {
			fail("IllegalArgumentException should not happen: "+e.getMessage());
		} catch (TransformerException e) {
			fail("TransformerException should not happen: "+e.getMessage());
		} catch (IOException e) {
			fail("IOException should not happen: "+e.getMessage());
		} catch (ParserConfigurationException e) {
			fail("ParserConfigurationException should not happen: "+e.getMessage());
		} catch (SAXException e) {
			fail("SAXException should not happen: "+e.getMessage());
		}


		assertNotNull(result);
		// Reading ZUGFeRD
		assertEquals(expected, result);
	}

/*	public void testPDFVisualization() {

	// the writing part
		CIIToUBL c2u = new CIIToUBL();
		String sourceFilename = "factur-x.xml";
		File CIIinputFile = getResourceAsFile(sourceFilename);

		String expected = null;
		String result = null;
		try {
			ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
			/* remove file endings so that tests can also pass after checking
			   out from git with arbitrary options (which may include CSRF changes)
			 *
			zvi.toPDF(CIIinputFile.getAbsolutePath(), "c:\\users\\jstaerk\\temp\\fopy2.pdf");
		} catch (UnsupportedOperationException e) {
			fail("UnsupportedOperationException should not happen: "+e.getMessage());
		} catch (IllegalArgumentException e) {
			fail("IllegalArgumentException should not happen: "+e.getMessage());
		}

//		assertNotNull(result);
		// Reading ZUGFeRD
//		assertEquals(expected, result);
	}*/

}
