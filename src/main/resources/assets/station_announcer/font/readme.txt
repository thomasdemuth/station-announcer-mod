texgyreheros-bold.otf — TeX Gyre Heros Bold 2.004, (c) GUST e-foundry
(B. Jackowski, J. M. Nowacki), https://www.gust.org.pl/projects/e-foundry/tex-gyre
Distributed UNMODIFIED under the GUST Font License (gust-font-license.txt, an
instance of the LaTeX Project Public License 1.3c). A free Helvetica-metric
face: the MTA sign system is set in Helvetica Medium, and this is the mod's
sign font (font id station_announcer:sign; glyphs it lacks fall back to MTR's).

Size and shift in sign.json are chosen so its capitals are exactly as tall,
and sit on exactly the same line, as MTR's Noto Sans at size 12 — vanilla's
STB loader scales a font so ascent-descent = size, so cap px = size*729/1432
(Noto: 12*714/1362) and the cap top = size*(1125-729)/1432 - 3 + shift.
