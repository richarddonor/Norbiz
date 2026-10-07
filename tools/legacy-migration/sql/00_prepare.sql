-- Indexes on the raw legacy copy. extract.py loads it without any, and drops and recreates the
-- schema on every run, so this runs once right after each extract.

CREATE INDEX ON legacy.tbltransactiondetails (typeid, masterid, detailid);
CREATE INDEX ON legacy.tbltransactions (typeid, id);
CREATE INDEX ON legacy.tblinventoryadjustmentdetails (masterid);
CREATE INDEX ON legacy.tbloutletinventoryadjustmentdetails (masterid);
CREATE INDEX ON legacy.tblstocktransferdetails (masterid);
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
ANALYZE;
