-- Indexes on the raw legacy copy. extract.py loads it without any, and drops and recreates the
-- schema on every run, so this runs once right after each extract.

CREATE INDEX ON legacy.tbltransactiondetails (typeid, masterid, detailid);
CREATE INDEX ON legacy.tbltransactions (typeid, id);
CREATE INDEX ON legacy.tblinventoryadjustmentdetails (masterid);
CREATE INDEX ON legacy.tbloutletinventoryadjustmentdetails (masterid);
CREATE INDEX ON legacy.tblstocktransferdetails (masterid);
CREATE INDEX ON legacy.tblstocktransferuseractions (masterid);
CREATE INDEX ON legacy.tbldeliveryreceiptdetails (drid);
CREATE INDEX ON legacy.tbloutletreceivedetails (masterid);
CREATE INDEX ON legacy.tbloutletreceivedetails (ancdetailid);
CREATE INDEX ON legacy.tbloutletdeliveryreceiptsdetails (odrid);
CREATE INDEX ON legacy.tbloutletdeliveryreturndetails (returnslipmasterid);
CREATE INDEX ON legacy.tblreturnslipdetails (returnslipmasterid);
CREATE INDEX ON legacy.tbloutletpulloutdetail (masterid);
CREATE INDEX ON legacy.tbloutletpulloutdetail (ancdetailid);
CREATE INDEX ON legacy.tblsupplierinvoicedetails (supplierinvoiceid);
CREATE INDEX ON legacy.tblitemreceivedetails (masterid);
CREATE INDEX ON legacy.tblassemblydetail (masterid);
CREATE INDEX ON legacy.tblassemblyrawmaterial (masterid);
CREATE INDEX ON legacy.tblstocktransfer (drid);
CREATE INDEX ON legacy.tblsalesinvoicedetails (salesinvoiceid);
CREATE INDEX ON legacy.tblsireturnslipdetails (returnslipmasterid);
CREATE INDEX ON legacy.tblcustomerpaymentdetails (customerpaymentid);
CREATE INDEX ON legacy.tblcustomerpaymentdetails (id);
CREATE INDEX ON legacy.tblcustomerpaymentlinks (customerpaymentdetailid);
CREATE INDEX ON legacy.tblcustomercreditmemos (customerpaymentdetailid);
CREATE INDEX ON legacy.tblcustomercreditmemos (uicustomerreturnid);
CREATE INDEX ON legacy.tbldepositdetails (paymentdetailid);
CREATE INDEX ON legacy.tblsupplierpaymentlinks (supplierpaymentdetailid);
ANALYZE;
