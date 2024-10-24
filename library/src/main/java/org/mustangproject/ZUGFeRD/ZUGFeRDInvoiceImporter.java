package org.mustangproject.ZUGFeRD;

import org.apache.commons.io.IOUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.mustangproject.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ZUGFeRDInvoiceImporter {

	private static final Logger LOGGER = LoggerFactory.getLogger(ZUGFeRDInvoiceImporter.class.getCanonicalName()); // log
	/**
	 * map filenames of additional XML files to their contents
	 */
	protected final HashMap<String, byte[]> additionalXMLs = new HashMap<>();
	/**
	 * map filenames of all embedded files in the respective PDF
	 */
	protected final ArrayList<FileAttachment> PDFAttachments = new ArrayList<>();
	/**
	 * if metadata has been found
	 */
	protected boolean containsMeta = false;
	/**
	 * Raw XML form of the extracted data - may be directly obtained.
	 */
	protected byte[] rawXML = null;
	/**
	 * XMP metadata
	 */
	protected String xmpString = null; // XMP metadata
	/**
	 * parsed Document
	 */
	protected Document document;
	/***
	 * automatically parse into importedInvoice
	 */
	protected boolean parseAutomatically = true;
	protected Integer version;
	protected CalculatedInvoice importedInvoice = null;
	protected boolean recalcPrice = false;
	protected boolean ignoreCalculationErrors = false;
	protected ArrayList<FileAttachment> fileAttachments = new ArrayList<>();

	public ZUGFeRDInvoiceImporter() {
		//constructor for extending classes
	}

	public ZUGFeRDInvoiceImporter(String pdfFilename) {
		setPDFFilename(pdfFilename);
	}

	public ZUGFeRDInvoiceImporter(InputStream pdfStream) {
		setInputStream(pdfStream);
	}

	public void setPDFFilename(String pdfFilename) {
		try (InputStream bis = Files.newInputStream(Paths.get(pdfFilename), StandardOpenOption.READ)) {
			extractLowLevel(bis);
		} catch (final IOException e) {
			LOGGER.error("Failed to extract ZUGFeRD data", e);
			throw new ZUGFeRDExportException(e);
		}
	}

	public void setInputStream(InputStream pdfStream) {
		try {
			extractLowLevel(pdfStream);
		} catch (final IOException e) {
			LOGGER.error("Failed to extract ZUGFeRD data", e);
			throw new ZUGFeRDExportException(e);
		}
	}

	/***
	 * return the file names of all files embedded into the PDF
	 * @see for XML embedded files please use ZUGFeRDInvoiceImporter.getFileAttachmentsXML
	 * @return a ArrayList of FileAttachments, empty if none
	 */
	public List<FileAttachment> getFileAttachmentsPDF() {
		return PDFAttachments;
	}

	/**
	 * Extracts a ZUGFeRD invoice from a PDF document represented by an input stream. Errors are reported via exception handling.
	 *
	 * @param inStream a inputstream of a pdf file
	 */
	private void extractLowLevel(InputStream inStream) throws IOException {
		BufferedInputStream pdfStream = new BufferedInputStream(inStream);
		byte[] pad = new byte[4];
		pdfStream.mark(0);
		pdfStream.read(pad);
		pdfStream.reset();
		byte[] pdfSignature = {'%', 'P', 'D', 'F'};
		if (Arrays.equals(pad, pdfSignature)) { // we have a pdf


			try {
				PDDocument doc = Loader.loadPDF(IOUtils.toByteArray(pdfStream));
				// PDDocumentInformation info = doc.getDocumentInformation();
				final PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
				//start

				if (doc.getDocumentCatalog() == null || doc.getDocumentCatalog().getMetadata() == null) {
					LOGGER.info("no-xmlpart");
					return;
				}

				final InputStream XMP = doc.getDocumentCatalog().getMetadata().exportXMPMetadata();

				xmpString = new String(XMLTools.getBytesFromStream(XMP), StandardCharsets.UTF_8);

				final PDEmbeddedFilesNameTreeNode etn = names.getEmbeddedFiles();
				if (etn == null) {
					return;
				}

				final Map<String, PDComplexFileSpecification> efMap = etn.getNames();
				// String filePath = "/tmp/";

				if (efMap != null) {
					extractFiles(efMap); // see
					// https://memorynotfound.com/apache-pdfbox-extract-embedded-file-pdf-document/
				} else {

					final List<PDNameTreeNode<PDComplexFileSpecification>> kids = etn.getKids();
					if (kids == null) {
						return;
					}
					for (final PDNameTreeNode<PDComplexFileSpecification> node : kids) {
						final Map<String, PDComplexFileSpecification> namesL = node.getNames();
						extractFiles(namesL);
					}
				}
			} catch (Exception e) {
				LOGGER.error("Failed to parse PDF", e);
				//ignore otherwise
			}
		} else {
			// no PDF probably XML
			containsMeta = true;
			setRawXML(XMLTools.getBytesFromStream(pdfStream));

		}
	}

	/***
	 * have the item prices be determined from the line total.
	 * That's a workaround for some invoices which just put 0 as item price
	 */
	public void doRecalculateItemPricesFromLineTotals() {
		recalcPrice = true;
	}

	/***
	 * do not raise ParseExceptions even if the reproduced invoice total does not match the given value
	 */
	public void doIgnoreCalculationErrors() {
		ignoreCalculationErrors = true;
	}

	/***
	 * sets th pdf attachments, and if a file is recognized (e.g. a factur-x.xml) triggers processing
	 * @param names the Hashmap of String, PDComplexFileSpecification
	 * @throws IOException
	 */
	private void extractFiles(Map<String, PDComplexFileSpecification> names) throws IOException {
		for (final String alias : names.keySet()) {

			final PDComplexFileSpecification fileSpec = names.get(alias);
			final String filename = fileSpec.getFilename();
			/**
			 * filenames for invoice data (ZUGFeRD v1 and v2, Factur-X)
			 */

			final PDEmbeddedFile embeddedFile = fileSpec.getEmbeddedFile();
			if ((filename.equals("ZUGFeRD-invoice.xml") || (filename.equals("zugferd-invoice.xml")) || filename.equals("factur-x.xml")) || filename.equals("xrechnung.xml") || filename.equals("order-x.xml") || filename.equals("cida.xml")) {
				containsMeta = true;

				// String embeddedFilename = filePath + filename;
				// File file = new File(filePath + filename);
				// System.out.println("Writing " + embeddedFilename);
				// ByteArrayOutputStream fileBytes=new
				// ByteArrayOutputStream();
				// FileOutputStream fos = new FileOutputStream(file);

				setRawXML(embeddedFile.toByteArray());

				// fos.write(embeddedFile.getByteArray());
				// fos.close();
			}
			if (filename.startsWith("additional_data")) {
				additionalXMLs.put(filename, embeddedFile.toByteArray());
			}
			PDFAttachments.add(new FileAttachment(filename, embeddedFile.getSubtype(), "Data", embeddedFile.toByteArray()));
		}
	}

	/***
	 * set the xml of a CII invoice
	 * @param rawXML the xml string
	 * @param doParse automatically parse input for zugferdImporter (not ZUGFeRDInvoiceImporter)
	 * @throws IOException
	 */
	public void setRawXML(byte[] rawXML, boolean doParse) throws IOException {
		this.containsMeta = true;
		this.rawXML = rawXML;
		this.version = null;
		parseAutomatically = doParse;

		try {
			setDocument();
		} catch (ParserConfigurationException | SAXException e) {
			LOGGER.error("Failed to parse XML", e);
			throw new ZUGFeRDExportException(e);
		}

	}

	/***
	 * set the xml of a CII invoice, simple version
	 * @param rawXML the cii(?) as a string
	 * @throws IOException
	 */
	public void setRawXML(byte[] rawXML) throws IOException {
		setRawXML(rawXML, true);
	}

	private void setDocument() throws ParserConfigurationException, IOException, SAXException {
		final DocumentBuilderFactory xmlFact = DocumentBuilderFactory.newInstance();
		xmlFact.setNamespaceAware(true);
		final DocumentBuilder builder = xmlFact.newDocumentBuilder();
		final ByteArrayInputStream is = new ByteArrayInputStream(rawXML);
		///    is.skip(guessBOMSize(is));
		document = builder.parse(is);
		if (parseAutomatically) {
			try {
				importedInvoice = new CalculatedInvoice();
				extractInto(importedInvoice);
			} catch (XPathExpressionException e) {
				throw new RuntimeException(e);
			} catch (ParseException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/***
	 * This will parse a XML into the given invoice object
	 * @param zpp the invoice to be altered
	 * @return the parsed invoice object
	 * @throws XPathExpressionException if xpath could not be evaluated
	 * @throws ParseException if the grand total of the parsed invoice could not be replicated with the new invoice
	 */
	public Invoice extractInto(Invoice zpp) throws XPathExpressionException, ParseException {

		String number = "";
		String typeCode = null;
		String deliveryPeriodStart = null;
		String deliveryPeriodEnd = null;
		/*
		 * dummywerte sind derzeit noch setDueDate setIssueDate setDeliveryDate
		 * setSender setRecipient setnumber bspw. due date
		 * //ExchangedDocument//IssueDateTime//DateTimeString : due date optional
		 */
		XPathFactory xpathFact = XPathFactory.newInstance();
		XPath xpath = xpathFact.newXPath();
		XPathExpression xpr = xpath.compile("//*[local-name()=\"SellerTradeParty\"]|//*[local-name()=\"AccountingSupplierParty\"]/*");
		NodeList SellerNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		XPathExpression shipEx = xpath.compile("//*[local-name()=\"ShipToTradeParty\"]");
		NodeList deliveryNodes = (NodeList) shipEx.evaluate(getDocument(), XPathConstants.NODESET);
		if (deliveryNodes != null) {
			zpp.setDeliveryAddress(new TradeParty(deliveryNodes));
		}


		xpr = xpath.compile("//*[local-name()=\"BuyerTradeParty\"]|//*[local-name()=\"AccountingCustomerParty\"]/*");
		NodeList BuyerNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		xpr = xpath.compile("//*[local-name()=\"PayeeTradeParty\"]|//*[local-name()=\"PayeeParty\"]/*");
		NodeList payeeNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		xpr = xpath.compile("//*[local-name()=\"ExchangedDocument\"]|//*[local-name()=\"HeaderExchangedDocument\"]");
		NodeList ExchangedDocumentNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		xpr = xpath.compile("//*[local-name()=\"GrandTotalAmount\"]|//*[local-name()=\"PayableAmount\"]");
		BigDecimal expectedGrandTotal = null;
		NodeList totalNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		if (totalNodes.getLength() > 0) {
			expectedGrandTotal = new BigDecimal(XMLTools.trimOrNull(totalNodes.item(0)));
			if (zpp instanceof CalculatedInvoice) {
				// usually we would re-calculate the invoice to get expectedGrandTotal
				// however, for "minimal" invoices or other invoices without lines
				// this will not work
				((CalculatedInvoice) zpp).setGrandTotal(expectedGrandTotal);
			}
		}

		xpr = xpath.compile("//*[local-name()=\"PrepaidAmount\"]");
		NodeList prepaidNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		if (prepaidNodes.getLength() > 0) {
			zpp.setTotalPrepaidAmount(new BigDecimal(XMLTools.trimOrNull(prepaidNodes.item(0))));
		}

		Date issueDate = null;
		Date dueDate = null;
		Date deliveryDate = null;
		String despatchAdviceReferencedDocument = null;
		for (int i = 0; i < ExchangedDocumentNodes.getLength(); i++) {
			Node exchangedDocumentNode = ExchangedDocumentNodes.item(i);
			NodeList exchangedDocumentChilds = exchangedDocumentNode.getChildNodes();
			for (int documentChildIndex = 0; documentChildIndex < exchangedDocumentChilds.getLength(); documentChildIndex++) {
				Node item = exchangedDocumentChilds.item(documentChildIndex);
				if ((item.getLocalName() != null) && (item.getLocalName().equals("ID"))) {
					number = XMLTools.trimOrNull(item);
				}
				if ((item.getLocalName() != null) && (item.getLocalName().equals("TypeCode"))) {
					typeCode = XMLTools.trimOrNull(item);
				}
				if ((item.getLocalName() != null) && (item.getLocalName().equals("IssueDateTime"))) {
					NodeList issueDateTimeChilds = item.getChildNodes();
					for (int issueDateChildIndex = 0; issueDateChildIndex < issueDateTimeChilds.getLength(); issueDateChildIndex++) {
						if ((issueDateTimeChilds.item(issueDateChildIndex).getLocalName() != null)
							&& (issueDateTimeChilds.item(issueDateChildIndex).getLocalName().equals("DateTimeString"))) {
							issueDate = new SimpleDateFormat("yyyyMMdd").parse(XMLTools.trimOrNull(issueDateTimeChilds.item(issueDateChildIndex)));
						}
					}
				}
			}
		}
		String rootNode = extractString("local-name(/*)");
		if (rootNode.equals("Invoice")) {
			// UBL...
			number = extractString("//*[local-name()=\"Invoice\"]/*[local-name()=\"ID\"]").trim();
			issueDate = new SimpleDateFormat("yyyy-MM-dd").parse(extractString("//*[local-name()=\"Invoice\"]/*[local-name()=\"IssueDate\"]").trim());
			String dueDt = extractString("//*[local-name()=\"Invoice\"]/*[local-name()=\"DueDate\"]").trim();
			if (dueDt.length() > 0) {
				dueDate = new SimpleDateFormat("yyyy-MM-dd").parse(dueDt);
			}
			String deliveryDt = extractString("//*[local-name()=\"Delivery\"]/*[local-name()=\"ActualDeliveryDate\"]").trim();
			if (deliveryDt.length() > 0) {
				deliveryDate = new SimpleDateFormat("yyyy-MM-dd").parse(deliveryDt);
			}
		}
		xpr = xpath.compile("//*[local-name()=\"ApplicableHeaderTradeDelivery\"]");
		NodeList headerTradeDeliveryNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		for (int i = 0; i < headerTradeDeliveryNodes.getLength(); i++) {
			Node headerTradeDeliveryNode = headerTradeDeliveryNodes.item(i);
			NodeList headerTradeDeliveryChilds = headerTradeDeliveryNode.getChildNodes();
			for (int deliveryChildIndex = 0; deliveryChildIndex < headerTradeDeliveryChilds.getLength(); deliveryChildIndex++) {
				if (headerTradeDeliveryChilds.item(deliveryChildIndex).getLocalName() != null) {
					if (headerTradeDeliveryChilds.item(deliveryChildIndex).getLocalName().equals("ActualDeliverySupplyChainEvent")) {
						NodeList actualDeliveryChilds = headerTradeDeliveryChilds.item(deliveryChildIndex).getChildNodes();
						for (int actualDeliveryChildIndex = 0; actualDeliveryChildIndex < actualDeliveryChilds.getLength(); actualDeliveryChildIndex++) {
							if ((actualDeliveryChilds.item(actualDeliveryChildIndex).getLocalName() != null)
								&& (actualDeliveryChilds.item(actualDeliveryChildIndex).getLocalName().equals("OccurrenceDateTime"))) {
								NodeList occurenceChilds = actualDeliveryChilds.item(actualDeliveryChildIndex).getChildNodes();
								for (int occurenceChildIndex = 0; occurenceChildIndex < occurenceChilds.getLength(); occurenceChildIndex++) {
									if ((occurenceChilds.item(occurenceChildIndex).getLocalName() != null)
										&& (occurenceChilds.item(occurenceChildIndex).getLocalName().equals("DateTimeString"))) {
										deliveryDate = new SimpleDateFormat("yyyyMMdd").parse(XMLTools.trimOrNull(occurenceChilds.item(occurenceChildIndex)));
									}
								}
							}
						}
					}

					if (headerTradeDeliveryChilds.item(deliveryChildIndex).getLocalName().equals("DespatchAdviceReferencedDocument")) {
						NodeList despatchAdviceChilds = headerTradeDeliveryChilds.item(deliveryChildIndex).getChildNodes();
						for (int despatchAdviceChildIndex = 0; despatchAdviceChildIndex < despatchAdviceChilds.getLength(); despatchAdviceChildIndex++) {
							if (despatchAdviceChilds.item(despatchAdviceChildIndex).getLocalName() != null
								&& despatchAdviceChilds.item(despatchAdviceChildIndex).getLocalName().equals("IssuerAssignedID")) {
								despatchAdviceReferencedDocument = XMLTools.trimOrNull(despatchAdviceChilds.item(despatchAdviceChildIndex));
							}
						}
					}
				}
			}
		}

		xpr = xpath.compile("//*[local-name()=\"ApplicableHeaderTradeAgreement\"]");
		NodeList headerTradeAgreementNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		String buyerOrderIssuerAssignedID = null;
		String sellerOrderIssuerAssignedID = null;
		for (int i = 0; i < headerTradeAgreementNodes.getLength(); i++) {
			// XMLTools.trimOrNull(nodes.item(i)))) {
			Node headerTradeAgreementNode = headerTradeAgreementNodes.item(i);
			NodeList headerTradeAgreementChilds = headerTradeAgreementNode.getChildNodes();
			for (int agreementChildIndex = 0; agreementChildIndex < headerTradeAgreementChilds.getLength(); agreementChildIndex++) {
				if (headerTradeAgreementChilds.item(agreementChildIndex).getLocalName() != null) {
					if (headerTradeAgreementChilds.item(agreementChildIndex).getLocalName().equals("BuyerOrderReferencedDocument")) {
						NodeList buyerOrderChilds = headerTradeAgreementChilds.item(agreementChildIndex).getChildNodes();
						for (int buyerOrderChildIndex = 0; buyerOrderChildIndex < buyerOrderChilds.getLength(); buyerOrderChildIndex++) {
							if ((buyerOrderChilds.item(buyerOrderChildIndex).getLocalName() != null)
								&& (buyerOrderChilds.item(buyerOrderChildIndex).getLocalName().equals("IssuerAssignedID"))) {
								buyerOrderIssuerAssignedID = XMLTools.trimOrNull(buyerOrderChilds.item(buyerOrderChildIndex));
							}
						}
					}

					if (headerTradeAgreementChilds.item(agreementChildIndex).getLocalName().equals("SellerOrderReferencedDocument")) {
						NodeList sellerOrderChilds = headerTradeAgreementChilds.item(agreementChildIndex).getChildNodes();
						for (int sellerOrderChildIndex = 0; sellerOrderChildIndex < sellerOrderChilds.getLength(); sellerOrderChildIndex++) {
							if ((sellerOrderChilds.item(sellerOrderChildIndex).getLocalName() != null)
								&& (sellerOrderChilds.item(sellerOrderChildIndex).getLocalName().equals("IssuerAssignedID"))) {
								sellerOrderIssuerAssignedID = XMLTools.trimOrNull(sellerOrderChilds.item(sellerOrderChildIndex));
							}
						}
					}
				}
			}

		}

		String currency = extractString("//*[local-name()=\"ApplicableHeaderTradeSettlement\"]/*[local-name()=\"InvoiceCurrencyCode\"]|*[local-name()=\"DocumentCurrencyCode\"]");
		zpp.setCurrency(currency);

		xpr = xpath.compile("//*[local-name()=\"ApplicableHeaderTradeSettlement\"]|//*[local-name()=\"ApplicableSupplyChainTradeSettlement\"]");
		NodeList headerTradeSettlementNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		List<BankDetails> bankDetails = new ArrayList<>();
		String directDebitMandateID = null;
		String IBAN = null, BIC = null;

		for (int i = 0; i < headerTradeSettlementNodes.getLength(); i++) {
			// XMLTools.trimOrNull(nodes.item(i)))) {
			Node headerTradeSettlementNode = headerTradeSettlementNodes.item(i);

			NodeList headerTradeSettlementChilds = headerTradeSettlementNode.getChildNodes();
			for (int settlementChildIndex = 0; settlementChildIndex < headerTradeSettlementChilds.getLength(); settlementChildIndex++) {
				if ((headerTradeSettlementChilds.item(settlementChildIndex).getLocalName() != null)
					&& (headerTradeSettlementChilds.item(settlementChildIndex).getLocalName().equals("SpecifiedTradePaymentTerms"))) {
					NodeList paymentTermChilds = headerTradeSettlementChilds.item(settlementChildIndex).getChildNodes();
					for (int paymentTermChildIndex = 0; paymentTermChildIndex < paymentTermChilds.getLength(); paymentTermChildIndex++) {
						if ((paymentTermChilds.item(paymentTermChildIndex).getLocalName() != null) && (paymentTermChilds.item(paymentTermChildIndex).getLocalName().equals("DueDateDateTime"))) {
							NodeList dueDateChilds = paymentTermChilds.item(paymentTermChildIndex).getChildNodes();
							for (int dueDateChildIndex = 0; dueDateChildIndex < dueDateChilds.getLength(); dueDateChildIndex++) {
								if ((dueDateChilds.item(dueDateChildIndex).getLocalName() != null) && (dueDateChilds.item(dueDateChildIndex).getLocalName().equals("DateTimeString"))) {
									dueDate = new SimpleDateFormat("yyyyMMdd").parse(XMLTools.trimOrNull(dueDateChilds.item(dueDateChildIndex)));
								}
							}
						}
						if ((paymentTermChilds.item(paymentTermChildIndex).getLocalName() != null) && (paymentTermChilds.item(paymentTermChildIndex).getLocalName().equals("DirectDebitMandateID"))) {
							directDebitMandateID = paymentTermChilds.item(paymentTermChildIndex).getTextContent();
						}
					}
				}

				if ((headerTradeSettlementChilds.item(settlementChildIndex).getLocalName() != null)
					&& (headerTradeSettlementChilds.item(settlementChildIndex).getLocalName().equals("SpecifiedTradeSettlementPaymentMeans"))) {
					NodeList paymentMeansChilds = headerTradeSettlementChilds.item(settlementChildIndex).getChildNodes();
					IBAN = null;
					BIC = null;
					for (int paymentMeansChildIndex = 0; paymentMeansChildIndex < paymentMeansChilds.getLength(); paymentMeansChildIndex++) {

						if ((paymentMeansChilds.item(paymentMeansChildIndex).getLocalName() != null) && (paymentMeansChilds.item(paymentMeansChildIndex).getLocalName().equals("PayeePartyCreditorFinancialAccount") || paymentMeansChilds.item(paymentMeansChildIndex).getLocalName().equals("PayerPartyDebtorFinancialAccount"))) {
							NodeList accountChilds = paymentMeansChilds.item(paymentMeansChildIndex).getChildNodes();
							for (int accountChildIndex = 0; accountChildIndex < accountChilds.getLength(); accountChildIndex++) {
								if ((accountChilds.item(accountChildIndex).getLocalName() != null) && (accountChilds.item(accountChildIndex).getLocalName().equals("IBANID"))) {//CII
									IBAN = XMLTools.trimOrNull(accountChilds.item(accountChildIndex));
								}
							}
						}
						if ((paymentMeansChilds.item(paymentMeansChildIndex).getLocalName() != null) && (paymentMeansChilds.item(paymentMeansChildIndex).getLocalName().equals("PayeeSpecifiedCreditorFinancialInstitution"))) {
							NodeList accountChilds = paymentMeansChilds.item(paymentMeansChildIndex).getChildNodes();
							for (int accountChildIndex = 0; accountChildIndex < accountChilds.getLength(); accountChildIndex++) {
								if ((accountChilds.item(accountChildIndex).getLocalName() != null) && (accountChilds.item(accountChildIndex).getLocalName().equals("BICID"))) {//CII
									BIC = XMLTools.trimOrNull(accountChilds.item(accountChildIndex));
								}
							}
						}

					}
					if (IBAN != null) {
						BankDetails bd = new BankDetails(IBAN);
						if (BIC != null) {
							bd.setBIC(BIC);
						}
						bankDetails.add(bd);
					}
				}
				if ((headerTradeSettlementChilds.item(settlementChildIndex).getLocalName() != null)
					&& (headerTradeSettlementChilds.item(settlementChildIndex).getLocalName().equals("BillingSpecifiedPeriod"))) {
					NodeList periodChilds = headerTradeSettlementChilds.item(settlementChildIndex).getChildNodes();
					for (int periodChildIndex = 0; periodChildIndex < periodChilds.getLength(); periodChildIndex++) {
						if ((periodChilds.item(periodChildIndex).getLocalName() != null) && (periodChilds.item(periodChildIndex).getLocalName().equals("StartDateTime"))) {

							NodeList startPeriodChilds = periodChilds.item(periodChildIndex).getChildNodes();
							for (int startPeriodIndex = 0; startPeriodIndex < startPeriodChilds.getLength(); startPeriodIndex++) {
								if ((startPeriodChilds.item(startPeriodIndex).getLocalName() != null) && (startPeriodChilds.item(startPeriodIndex).getLocalName().equals("DateTimeString"))) {//CII
									deliveryPeriodStart = XMLTools.trimOrNull(startPeriodChilds.item(startPeriodIndex));
								}
							}
						}
						if ((periodChilds.item(periodChildIndex).getLocalName() != null) && (periodChilds.item(periodChildIndex).getLocalName().equals("EndDateTime"))) {
							NodeList endPeriodChilds = periodChilds.item(periodChildIndex).getChildNodes();
							for (int endPeriodIndex = 0; endPeriodIndex < endPeriodChilds.getLength(); endPeriodIndex++) {
								if ((endPeriodChilds.item(endPeriodIndex).getLocalName() != null) && (endPeriodChilds.item(endPeriodIndex).getLocalName().equals("DateTimeString"))) {//CII
									deliveryPeriodEnd = XMLTools.trimOrNull(endPeriodChilds.item(endPeriodIndex));
								}
							}
						}
					}
				}
			}
		}

		if ((deliveryPeriodStart != null) && (deliveryPeriodEnd != null)) {
			zpp.setDetailedDeliveryPeriod(XMLTools.tryDate(deliveryPeriodStart), XMLTools.tryDate(deliveryPeriodEnd));
		} else if (deliveryPeriodStart != null) {
			zpp.setDeliveryDate(XMLTools.tryDate(deliveryPeriodStart));
		}

		xpr = xpath.compile("//*[local-name()=\"PaymentMeans\"]"); //UBL only
		NodeList paymentMeansNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		for (int i = 0; i < paymentMeansNodes.getLength(); i++) {
			// XMLTools.trimOrNull(nodes.item(i)))) {
			Node paymentMeansNode = paymentMeansNodes.item(i);
			NodeList paymentMeansChilds = paymentMeansNode.getChildNodes();
			for (int meansChildIndex = 0; meansChildIndex < paymentMeansChilds.getLength(); meansChildIndex++) {
				if ((paymentMeansChilds.item(meansChildIndex).getLocalName() != null)
					&& (paymentMeansChilds.item(meansChildIndex).getLocalName().equals("PayeeFinancialAccount"))) {
					NodeList paymentTermChilds = paymentMeansChilds.item(meansChildIndex).getChildNodes();
					for (int paymentTermChildIndex = 0; paymentTermChildIndex < paymentTermChilds.getLength(); paymentTermChildIndex++) {
						if ((paymentTermChilds.item(paymentTermChildIndex).getLocalName() != null) && (paymentTermChilds.item(paymentTermChildIndex).getLocalName().equals("ID"))) {
							IBAN = XMLTools.trimOrNull(paymentTermChilds.item(paymentTermChildIndex));
							if (IBAN != null) {
								BankDetails bd = new BankDetails(IBAN);
								bankDetails.add(bd);
							}
						}
					}
				}
			}
		}

		zpp.setDueDate(dueDate).setDeliveryDate(deliveryDate).setIssueDate(issueDate).setSender(new TradeParty(SellerNodes)).setRecipient(new TradeParty(BuyerNodes)).setNumber(number).setDocumentCode(typeCode);

		if ((directDebitMandateID != null) && (IBAN != null)) {
			DirectDebit d = new DirectDebit(IBAN, directDebitMandateID);
			zpp.getSender().addDebitDetails(d);
		}

		bankDetails.forEach(bankDetail -> zpp.getSender().addBankDetails(bankDetail));

		if (payeeNodes.getLength() > 0) {
			zpp.setPayee(new TradeParty(payeeNodes));
		}

		if (buyerOrderIssuerAssignedID != null) {
			zpp.setBuyerOrderReferencedDocumentID(buyerOrderIssuerAssignedID);
		}
		if (sellerOrderIssuerAssignedID != null) {
			zpp.setSellerOrderReferencedDocumentID(sellerOrderIssuerAssignedID);
		}
		if (despatchAdviceReferencedDocument != null) {
			zpp.setDespatchAdviceReferencedDocumentID(despatchAdviceReferencedDocument);
		}

		zpp.setOwnOrganisationName(extractString("//*[local-name()=\"SellerTradeParty\"]/*[local-name()=\"Name\"]|//*[local-name()=\"AccountingSupplierParty\"]/*[local-name()=\"Party\"]/*[local-name()=\"PartyName\"]").trim());

		xpr = xpath.compile("//*[local-name()=\"BuyerReference\"]");
		String buyerReference = null;
		prepaidNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
		if (prepaidNodes.getLength() > 0) {
			buyerReference = XMLTools.trimOrNull(prepaidNodes.item(0));
		}
		if (buyerReference != null) {
			zpp.setReferenceNumber(buyerReference);
		}

		xpr = xpath.compile("//*[local-name()=\"IncludedSupplyChainTradeLineItem\"]|//*[local-name()=\"InvoiceLine\"]");
		NodeList nodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);

		if (nodes.getLength() != 0) {
			for (int i = 0; i < nodes.getLength(); i++) {

				Node currentItemNode = nodes.item(i);
				Item it = new Item(currentItemNode.getChildNodes(), recalcPrice);
				zpp.addItem(it);

			}

			// now handling base64 encoded attachments AttachmentBinaryObject=CII, EmbeddedDocumentBinaryObject=UBL
			xpr = xpath.compile("//*[local-name()=\"AttachmentBinaryObject\"]|//*[local-name()=\"EmbeddedDocumentBinaryObject\"]");
			NodeList attachmentNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
			for (int i = 0; i < attachmentNodes.getLength(); i++) {
				FileAttachment fa = new FileAttachment(attachmentNodes.item(i).getAttributes().getNamedItem("filename").getNodeValue(), attachmentNodes.item(i).getAttributes().getNamedItem("mimeCode").getNodeValue(), "Data", Base64.getDecoder().decode(XMLTools.trimOrNull(attachmentNodes.item(i))));
				fileAttachments.add(fa);
				// filename = "Aufmass.png" mimeCode = "image/png"
				//EmbeddedDocumentBinaryObject cbc:EmbeddedDocumentBinaryObject mimeCode="image/png" filename="Aufmass.png"
			}

			// item level charges+allowances are not yet handled but a lower item price will
			// be read,
			// so the invoice remains arithmetically correct
			// -> parse document level charges+allowances
			xpr = xpath.compile("//*[local-name()=\"SpecifiedTradeAllowanceCharge\"]");
			NodeList chargeNodes = (NodeList) xpr.evaluate(getDocument(), XPathConstants.NODESET);
			for (int i = 0; i < chargeNodes.getLength(); i++) {
				NodeList chargeNodeChilds = chargeNodes.item(i).getChildNodes();
				boolean isCharge = true;
				String chargeAmount = null;
				String reason = null;
				String reasonCode = null;
				String taxPercent = null;
				for (int chargeChildIndex = 0; chargeChildIndex < chargeNodeChilds.getLength(); chargeChildIndex++) {
					String chargeChildName = chargeNodeChilds.item(chargeChildIndex).getLocalName();
					if (chargeChildName != null) {

						if (chargeChildName.equals("ChargeIndicator")) {
							NodeList indicatorChilds = chargeNodeChilds.item(chargeChildIndex).getChildNodes();
							for (int indicatorChildIndex = 0; indicatorChildIndex < indicatorChilds.getLength(); indicatorChildIndex++) {
								if ((indicatorChilds.item(indicatorChildIndex).getLocalName() != null)
									&& (indicatorChilds.item(indicatorChildIndex).getLocalName().equals("Indicator"))) {
									isCharge = XMLTools.trimOrNull(indicatorChilds.item(indicatorChildIndex)).equalsIgnoreCase("true");
								}
							}
						} else if (chargeChildName.equals("ActualAmount")) {
							chargeAmount = XMLTools.trimOrNull(chargeNodeChilds.item(chargeChildIndex));
						} else if (chargeChildName.equals("Reason")) {
							reason = XMLTools.trimOrNull(chargeNodeChilds.item(chargeChildIndex));
						} else if (chargeChildName.equals("ReasonCode")) {
							reasonCode = XMLTools.trimOrNull(chargeNodeChilds.item(chargeChildIndex));
						} else if (chargeChildName.equals("CategoryTradeTax")) {
							NodeList taxChilds = chargeNodeChilds.item(chargeChildIndex).getChildNodes();
							for (int taxChildIndex = 0; taxChildIndex < taxChilds.getLength(); taxChildIndex++) {
								String taxItemName = taxChilds.item(taxChildIndex).getLocalName();
								if ((taxItemName != null) && (taxItemName.equals("RateApplicablePercent") || taxItemName.equals("ApplicablePercent"))) {
									taxPercent = XMLTools.trimOrNull(taxChilds.item(taxChildIndex));
								}
							}
						}
					}
				}

				if (isCharge) {
					Charge c = new Charge(new BigDecimal(chargeAmount));
					if (reason != null) {
						c.setReason(reason);
					}
					if (reasonCode != null) {
						c.setReasonCode(reasonCode);
					}
					if (taxPercent != null) {
						c.setTaxPercent(new BigDecimal(taxPercent));
					}
					zpp.addCharge(c);
				} else {
					Allowance a = new Allowance(new BigDecimal(chargeAmount));
					if (reason != null) {
						a.setReason(reason);
					}
					if (reasonCode != null) {
						a.setReasonCode(reasonCode);
					}
					if (taxPercent != null) {
						a.setTaxPercent(new BigDecimal(taxPercent));
					}
					zpp.addAllowance(a);
				}

			}

			TransactionCalculator tc = new TransactionCalculator(zpp);
			String expectedStringTotalGross = tc.getGrandTotal()
				.subtract(zpp.getTotalPrepaidAmount() != null ? zpp.getTotalPrepaidAmount() : BigDecimal.ZERO).toPlainString();
			EStandard whichType;
			try {
				whichType = getStandard();
			} catch (Exception e) {
				throw new ParseException("Could not find out if it's an invoice, order, or delivery advice", 0);

			}

			if ((whichType != EStandard.despatchadvice)
				&& ((!expectedStringTotalGross.equals(XMLTools.nDigitFormat(expectedGrandTotal, 2)))
				&& (!ignoreCalculationErrors))) {
				throw new ParseException(
					"Could not reproduce the invoice, this could mean that it could not be read properly exp "+expectedStringTotalGross+" is "+XMLTools.nDigitFormat(expectedGrandTotal, 2), 0);
			}
		}
		return zpp;

	}

	protected Document getDocument() {
		return document;
	}

	protected String extractString(String xpathStr) {
		if (!containsMeta) {
			throw new ZUGFeRDExportException("No suitable data/ZUGFeRD file could be found.");
		}
		final String result;
		try {
			final Document document = getDocument();
			final XPathFactory xpathFact = XPathFactory.newInstance();
			final XPath xpath = xpathFact.newXPath();
			result = xpath.evaluate(xpathStr, document);
		} catch (final XPathExpressionException e) {
			LOGGER.error("Failed to evaluate XPath", e);
			throw new ZUGFeRDExportException(e);
		}
		return result;
	}

	public EStandard getStandard() throws Exception {
		if (!containsMeta) {
			throw new Exception("Not yet parsed");
		}
		final String head = getUTF8();
		String rootNode = extractString("local-name(/*)");
		if (rootNode.equals("CrossIndustryDocument")) {
			return EStandard.zugferd;
		} else if (rootNode.equals("Invoice")) {
			return EStandard.ubl;
		} else if (rootNode.equals("CreditNote")) {
			return EStandard.ubl;
		} else if (rootNode.equals("CrossIndustryInvoice")) {
			return EStandard.facturx;
		} else if (rootNode.equals("SCRDMCCBDACIDAMessageStructure")) {
			return EStandard.despatchadvice;
		} else if (head.contains("<rsm:SCRDMCCBDACIOMessageStructure")) {
			return EStandard.orderx;
		}

		throw new Exception("ZUGFeRD version could not be determined");

	}

	/**
	 * @return return UTF8 XML (without BOM) of the invoice
	 */
	public String getUTF8() {
		if (rawXML == null) {
			return null;
		}
		if (rawXML.length < 3) {
			return new String(rawXML);
		}


		final byte[] bomlessData;

		if ((rawXML[0] == (byte) 0xEF)
			&& (rawXML[1] == (byte) 0xBB)
			&& (rawXML[2] == (byte) 0xBF)) {
			// I don't like BOMs, lets remove it
			bomlessData = new byte[rawXML.length - 3];
			System.arraycopy(rawXML, 3, bomlessData, 0,
				rawXML.length - 3);
		} else {
			bomlessData = rawXML;
		}

		return new String(bomlessData);
	}

	/***
	 *
	 * @return the file attachments embedded in XML (using base64) decoded as byte array,
	 * for PDF embedded files in FX use getFileAttachmentsPDF()
	 */
	public List<FileAttachment> getFileAttachmentsXML() {
		return fileAttachments;
	}

	/***
	 * This will parse a XML into a invoice object
	 *
	 * @return the parsed invoice object
	 * @throws XPathExpressionException if internal xpath expressions were wrong
	 * @throws ParseException if the grand total of the parsed invoice could not be replicated with the new invoice
	 */
	public Invoice extractInvoice() throws XPathExpressionException, ParseException {
		Invoice i = new Invoice();
		return extractInto(i);


	}


	/***
	 * sets the XML for the importer to parse
	 * @param XML the UBL or CII
	 */
	public void fromXML(String XML) {
		try {
			containsMeta = true;
			setRawXML(XML.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

}
