var HEADERS = ["ДАТА", "НОМЕР", "ЗАМЕТКА", "НЕСОГЛАСОВАННЫЙ ВЫЕЗД", "ПУТЬ К ФОТО", "ID"];

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    var sheet = sheet_();
    if (body.action === "ids") {
      return json({ ok: true, ids: existingIds_(sheet) });
    }
    if (body.action === "append") {
      var result = appendRows_(sheet, body.rows || []);
      return json({ ok: true, inserted: result.inserted, skipped: result.skipped });
    }
    return json({ ok: false, error: "Неизвестное действие" });
  } catch (err) {
    return json({ ok: false, error: String(err) });
  }
}

function sheet_() {
  var ss = SpreadsheetApp.getActive();
  var sheet = ss.getSheets()[0];
  try {
    sheet.setName("TransportnyyeTekhnologii");
  } catch (ignore) {}
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]).setFontWeight("bold");
    sheet.setColumnWidth(1, 160);
    sheet.setColumnWidth(2, 120);
    sheet.setColumnWidth(3, 180);
    sheet.setColumnWidth(4, 220);
    sheet.setColumnWidth(5, 280);
    sheet.hideColumns(6);
  }
  return sheet;
}

function existingIds_(sheet) {
  var last = sheet.getLastRow();
  if (last < 2) return [];
  var values = sheet.getRange(2, 6, last - 1, 1).getValues();
  var ids = [];
  for (var i = 0; i < values.length; i++) {
    var id = String(values[i][0] || "");
    if (id) ids.push(id);
  }
  return ids;
}

function appendRows_(sheet, rows) {
  var known = {};
  existingIds_(sheet).forEach(function (id) { known[id] = true; });
  var toAdd = [];
  var skipped = 0;
  rows.forEach(function (row) {
    var id = String(row.id || "");
    if (!id || known[id]) {
      skipped++;
      return;
    }
    known[id] = true;
    toAdd.push([
      row.date || "",
      row.number || "",
      row.note || "",
      row.unauthorized ? "ДА" : "",
      row.photo || "",
      id
    ]);
  });
  if (toAdd.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, toAdd.length, HEADERS.length).setValues(toAdd);
  }
  return { inserted: toAdd.length, skipped: skipped };
}

function json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
