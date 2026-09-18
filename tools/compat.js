// License: GPLv3 Copyright: 2026 ebook-viewer-android contributors
//
// Platform compat preamble prepended to viewer.js at build time
// (tools/build_viewer_js.py). E-ink readers ship frozen, vendor WebViews
// below the Chromium 98 that introduced window.structuredClone — an
// iReader Smart Air Pro (Android 11) crashed at viewer.js start_book_load
// without this. Everything else viewer.js uses parses and runs on those
// WebViews (verified by API scan: no .at/replaceAll/hasOwn/findLast/
// randomUUID/Promise.any/groupBy/flatMap/queueMicrotask), so this single
// shim is the whole old-WebView story for now.
//
// Deliberately ES5 (no let/const/arrow/for-of/template literals) so the
// preamble itself cannot lower the syntax baseline of the bundle.
window.structuredClone = window.structuredClone || function (value) {
    function clone(v, seen) {
        if (v === null || typeof v !== 'object') return v;
        for (var i = 0; i < seen.length; i++) {
            if (seen[i][0] === v) return seen[i][1];
        }
        var copy;
        if (Object.prototype.toString.call(v) === '[object Date]') {
            copy = new Date(v.getTime());
        } else if (Object.prototype.toString.call(v) === '[object RegExp]') {
            copy = new RegExp(v.source, (v.flags || ''));
        } else if (typeof ArrayBuffer !== 'undefined' && (
            v instanceof ArrayBuffer ||
            (ArrayBuffer.isView && ArrayBuffer.isView(v)))) {
            var byteOffset = (v.byteOffset || 0);
            var buffer = new ArrayBuffer(v.byteLength !== undefined ? v.byteLength : v.buffer.byteLength);
            if (v instanceof ArrayBuffer) {
                buffer = new ArrayBuffer(v.byteLength);
                byteOffset = 0;
                copy = buffer;
            } else {
                copy = (Object.prototype.toString.call(v) === '[object DataView]')
                    ? new DataView(buffer)
                    : new v.constructor(buffer);
            }
            new Uint8Array(buffer).set(new Uint8Array(
                (v instanceof ArrayBuffer) ? v : v.buffer, byteOffset, v.byteLength));
        } else if (typeof Map !== 'undefined' && v instanceof Map) {
            copy = new Map();
        } else if (typeof Set !== 'undefined' && v instanceof Set) {
            copy = new Set();
        } else if (Object.prototype.toString.call(v) === '[object Array]') {
            copy = [];
        } else {
            copy = {};
        }
        seen.push([v, copy]);
        if (copy instanceof Map) {
            v.forEach(function (val, key) { copy.set(clone(key, seen), clone(val, seen)); });
        } else if (copy instanceof Set) {
            v.forEach(function (val) { copy.add(clone(val, seen)); });
        } else if (Object.prototype.toString.call(v) === '[object Array]' ||
                   Object.prototype.toString.call(v) === '[object Object]') {
            var keys = Object.keys(v);
            for (var k = 0; k < keys.length; k++) {
                copy[keys[k]] = clone(v[keys[k]], seen);
            }
        }
        return copy;
    }
    return clone(value, []);
};
