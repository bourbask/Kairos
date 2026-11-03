#!/usr/bin/env python3
"""Diagnostic scoring Kairos : lit la DB SQLite et montre la répartition des scores.
Usage (sur le VPS) : python3 diag_scores.py [chemin_db]
"""
import sqlite3
import sys

db = sys.argv[1] if len(sys.argv) > 1 else "data/kairos.db"
c = sqlite3.connect(db)

def rows(q):
    return list(c.execute(q))

print("== ADZUNA — 15 meilleures ==")
for r in rows(
    "select round(score,2), substr(title,1,42), substr(coalesce(location,''),1,20), remote "
    "from job_offers where source='adzuna' order by score desc limit 15"
):
    print(r)

print("\n== ADZUNA — répartition ==")
total = rows("select count(*) from job_offers where source='adzuna'")[0][0]
gated = rows("select count(*) from job_offers where source='adzuna' and score=0.0")[0][0]
passing = rows("select count(*) from job_offers where source='adzuna' and score>=0.6")[0][0]
mid = total - gated - passing
print(f"total={total}  gate_0.0={gated}  entre_0_et_0.6={mid}  >=0.6={passing}")

print("\n== TOP GLOBAL (toutes sources) ==")
for r in rows(
    "select source, round(score,2), substr(title,1,42) "
    "from job_offers order by score desc limit 10"
):
    print(r)

print("\n== combien d'offres FR (Adzuna) sont remote-flag ==")
for r in rows(
    "select remote, count(*) from job_offers where source='adzuna' group by remote"
):
    print(r)

print("\n== presented vs non-presented (>=0.6, toutes sources) ==")
for r in rows(
    "select presented, count(*) from job_offers where score>=0.6 group by presented"
):
    print(r)

print("\n== NON-presentees >=0.6 : combien + collectees quand ==")
print("total non-presentees >=0.6 :",
      rows("select count(*) from job_offers where score>=0.6 and presented=0")[0][0])
print("dont collectees aujourd'hui :",
      rows("select count(*) from job_offers where score>=0.6 and presented=0 and date(collected_at)=date('now')")[0][0])

print("\n== top 12 NON-presentees >=0.6 ==")
for r in rows(
    "select source, round(score,2), substr(title,1,40), date(collected_at) "
    "from job_offers where score>=0.6 and presented=0 order by score desc limit 12"
):
    print(r)
