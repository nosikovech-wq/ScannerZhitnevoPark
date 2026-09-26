var HEADERS = ["ДАТА", "НОМЕР", "ЗАМЕТКА", "НЕСОГЛАСОВАННЫЙ ВЫЕЗД", "ПУТЬ К ФОТО", "ID"];

function authorizeDrive() {
  var folder = photosFolder_();
  Logger.log(folder.getUrl());
}

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    var sheet = sheet_();
    if (body.action === "ids" || body.action === "status") {
      return json({ ok: true, ids: existingIds_(sheet), rows: statusRows_(sheet) });
    }
    if (body.action === "clear") {
      clearSheet_(sheet);
      return json({ ok: true });
    }
    if (body.action === "append") {
      var result = appendRows_(sheet, body.rows || []);
      return json({ ok: true, inserted: result.inserted, skipped: result.skipped });
    }
    if (body.action === "finish") {
      sortByDate_(sheet);
      formatSheet_(sheet);
      return json({ ok: true });
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

function clearSheet_(sheet) {
  var last = sheet.getLastRow();
  if (last > 1) {
    var links = sheet.getRange(2, 5, last - 1, 1).getValues();
    for (var i = 0; i < links.length; i++) trashDriveFile_(links[i][0]);
    sheet.deleteRows(2, last - 1);
  }
}

function statusRows_(sheet) {
  var last = sheet.getLastRow();
  if (last < 2) return [];
  var values = sheet.getRange(2, 5, last - 1, 2).getValues();
  var rows = [];
  for (var i = 0; i < values.length; i++) {
    var photo = String(values[i][0] || "");
    var id = String(values[i][1] || "");
    if (!id) continue;
    rows.push({ id: id, hasPhoto: photo.indexOf("https://drive.google.com/") === 0 });
  }
  return rows;
}

function photosFolder_() {
  var ss = SpreadsheetApp.getActive();
  var file = DriveApp.getFileById(ss.getId());
  var parent = file.getParents().hasNext() ? file.getParents().next() : DriveApp.getRootFolder();
  var found = parent.getFoldersByName("TransportnyyeTekhnologii-фото");
  return found.hasNext() ? found.next() : parent.createFolder("TransportnyyeTekhnologii-фото");
}

function savePhoto_(row, folder) {
  var data = row.photoData || "";
  if (!data) return "";
  var bytes = Utilities.base64Decode(data);
  var name = String(row.number || "plate").replace(/[\\/:*?"<>|]/g, "_") + "_" + String(row.id || "") + ".jpg";
  var blob = Utilities.newBlob(bytes, "image/jpeg", name);
  var file = folder.createFile(blob);
  try {
    file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  } catch (ignore) {}
  return "https://drive.google.com/file/d/" + file.getId() + "/view";
}

function trashDriveFile_(value) {
  var match = String(value || "").match(/\/d\/([a-zA-Z0-9_-]+)/);
  if (!match) return;
  try {
    DriveApp.getFileById(match[1]).setTrashed(true);
  } catch (ignore) {}
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
  var index = rowIndexById_(sheet);
  var folder = null;
  var toAdd = [];
  var skipped = 0;
  var updated = 0;
  rows.forEach(function (row) {
    var id = String(row.id || "");
    if (!id) {
      skipped++;
      return;
    }
    if (row.photoData && !folder) folder = photosFolder_();
    var photoUrl = folder ? savePhoto_(row, folder) : "";
    if (typeof index[id] === "number") {
      if (photoUrl) {
        sheet.getRange(index[id], 5).setValue(photoUrl);
        updated++;
      } else {
        skipped++;
      }
      return;
    }
    if (index[id]) {
      skipped++;
      return;
    }
    index[id] = true;
    toAdd.push([
      row.date || "",
      row.number || "",
      row.note || "",
      row.unauthorized ? "ДА" : "",
      photoUrl,
      id
    ]);
  });
  if (toAdd.length) {
    sheet.getRange(sheet.getLastRow() + 1, 1, toAdd.length, HEADERS.length).setValues(toAdd);
  }
  return { inserted: toAdd.length + updated, skipped: skipped };
}

function rowIndexById_(sheet) {
  var last = sheet.getLastRow();
  var index = {};
  if (last < 2) return index;
  var ids = sheet.getRange(2, 6, last - 1, 1).getValues();
  for (var i = 0; i < ids.length; i++) {
    var id = String(ids[i][0] || "");
    if (id) index[id] = i + 2;
  }
  return index;
}

function sortByDate_(sheet) {
  var last = sheet.getLastRow();
  if (last < 3) return;
  var range = sheet.getRange(2, 1, last - 1, HEADERS.length);
  var values = range.getValues();
  values.sort(function (a, b) {
    return dateValue_(a[0]) - dateValue_(b[0]);
  });
  range.setValues(values);
}

function dateValue_(value) {
  if (Object.prototype.toString.call(value) === "[object Date]" && !isNaN(value.getTime())) {
    return value.getTime();
  }
  var text = String(value || "");
  var match = text.match(/^(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!match) return 0;
  return new Date(
    Number(match[3]),
    Number(match[2]) - 1,
    Number(match[1]),
    Number(match[4] || 0),
    Number(match[5] || 0),
    Number(match[6] || 0)
  ).getTime();
}

function formatSheet_(sheet) {
  var last = sheet.getLastRow();
  if (last < 1) return;
  var header = sheet.getRange(1, 1, 1, HEADERS.length);
  header.setFontWeight("bold");
  header.setHorizontalAlignment("center");
  header.setVerticalAlignment("middle");
  sheet.setColumnWidth(5, 360);
  if (last < 2) return;
  var data = sheet.getRange(2, 1, last - 1, HEADERS.length);
  data.setHorizontalAlignment("left");
  data.setVerticalAlignment("middle");
  var flags = sheet.getRange(2, 4, last - 1, 1).getValues();
  var backgrounds = [];
  var colors = [];
  for (var i = 0; i < flags.length; i++) {
    var mark = String(flags[i][0] || "").trim().toLowerCase() === "да";
    backgrounds.push([mark ? "#e23b3b" : "#ffffff"]);
    colors.push([mark ? "#ffffff" : "#000000"]);
  }
  var flagRange = sheet.getRange(2, 4, last - 1, 1);
  flagRange.setBackgrounds(backgrounds);
  flagRange.setFontColors(colors);
  if (flags.length) flagRange.setFontWeight("bold");
}

function json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
