"use strict";
/* Portal Meta AI — orb visuals. Native code (MainActivity) drives state via
 * window.setOrb / showUser / showMeta; the orb tap calls back into Android. */

function $(id) { return document.getElementById(id); }

var HINTS = {
  idle: 'Tap the orb or say <b>“Hi Meta”</b> to start',
  listening: 'Listening… say <b>“Meta Stop”</b> to end',
  thinking: 'Thinking…',
  speaking: 'Meta is speaking…',
  nomic: 'Microphone permission needed'
};
var STATES = ["idle", "listening", "thinking", "speaking", "nomic"];

window.setOrb = function (state) {
  var app = $("app");
  STATES.forEach(function (s) { app.classList.remove(s); });
  app.classList.add(state);
  $("hint").innerHTML = HINTS[state] || "";
  $("status").textContent = (state === "idle" || state === "nomic") ? "Meta AI" : "Meta AI · active";
};

window.showUser = function (t) {
  var el = $("user");
  el.textContent = t || "";
  el.classList.toggle("show", !!(t && t.trim()));
};
window.showMeta = function (t) {
  var el = $("meta");
  el.textContent = t || "";
  el.classList.toggle("show", !!(t && t.trim()));
};

(function () {
  $("orb").addEventListener("click", function () {
    if (window.Android && window.Android.onOrbTap) window.Android.onOrbTap();
  });
  $("home-btn").addEventListener("click", function () {
    if (window.Android && window.Android.goAppHome) window.Android.goAppHome();
  });
  window.setOrb("idle");
})();
