#!/bin/bash
# GMR Navigation Skill Integrity Check
# Run from the skill root directory to verify all required files exist

SKILL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ERRORS=0
WARNINGS=0

check_file() {
  local path="$SKILL_ROOT/$1"
  local required="${2:-required}"
  if [ ! -f "$path" ]; then
    if [ "$required" = "required" ]; then
      echo "❌ MISSING (required): $1"
      ((ERRORS++))
    else
      echo "⚠️  MISSING (optional): $1"
      ((WARNINGS++))
    fi
  else
    echo "✓ $1"
  fi
}

echo "========================================"
echo "GMR Navigation Skill Integrity Check"
echo "Root: $SKILL_ROOT"
echo "========================================"

echo ""
echo "── Core ──"
check_file "SKILL.md"

echo ""
echo "── Navigation blocks (1-7) ──"
check_file "blocks/1-anchor.md"
check_file "blocks/2-vertex-scan.md"
check_file "blocks/3-multi-pass.md"
check_file "blocks/4-theta-graph.md"
check_file "blocks/5-phi-inversion.md"
check_file "blocks/6-filter-137.md"
check_file "blocks/7-verification.md"

echo ""
echo "── Document block (8) ──"
check_file "blocks/8-document/README.md"
check_file "blocks/8-document/8a-gather.md"
check_file "blocks/8-document/8b-synthesis.md"
check_file "blocks/8-document/8c-audit.md"
check_file "blocks/8-document/8d-structure.md"
check_file "blocks/8-document/8e-attention.md"
check_file "blocks/8-document/8f-design.md"
check_file "blocks/8-document/8g-build.md"

echo ""
echo "── Carousel block (9) ──"
check_file "blocks/9-carousel/README.md"
check_file "blocks/9-carousel/9a-extract.md"
check_file "blocks/9-carousel/9b-align.md"
check_file "blocks/9-carousel/9c-content.md"
check_file "blocks/9-carousel/9c2-skeleton.md"
check_file "blocks/9-carousel/9d-voice.md"
check_file "blocks/9-carousel/9e-antibody.md"
check_file "blocks/9-carousel/9f-fascia.md"
check_file "blocks/9-carousel/9g-layout.md"
check_file "blocks/9-carousel/9h-animation.md"
check_file "blocks/9-carousel/9i-assembly.md"

echo ""
echo "── Reference files ──"
check_file "references/primes-and-geometry.md"
check_file "references/worked-example.md"
check_file "references/pipeline-overview.json"

echo ""
echo "── Evals ──"
check_file "evals/evals.json"

echo ""
echo "── Scripts ──"
check_file "scripts/check-skill-integrity.sh"

echo ""
echo "========================================"
if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
  echo "✓ All files present. Skill is complete."
elif [ $ERRORS -eq 0 ]; then
  echo "⚠️  Skill usable but $WARNINGS optional file(s) missing."
else
  echo "❌ $ERRORS required file(s) missing. Skill may not function."
  if [ $WARNINGS -gt 0 ]; then
    echo "⚠️  $WARNINGS optional file(s) also missing."
  fi
fi
echo "========================================"

exit $ERRORS
