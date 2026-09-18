cd '/sdcard/rustedWarfare/units/人机的玩笑' || exit 1
for k in copyFrom image imageShadow iconImage shoot_sound soundOnDeath soundOnAttackOrder soundOnMoveOrder soundOnNewSelection tags effects convertTo; do
  t=$(grep -rnE "^[[:space:]]*$k[[:space:]]*:" . | wc -l)
  m=$(grep -rnE "^[[:space:]]*$k[[:space:]]*:.*," . | wc -l)
  echo "$k total=$t multi=$m"
done
echo '=== copyFrom samples'
grep -rnE "^[[:space:]]*copyFrom[[:space:]]*:" . | head -12
echo '=== copyFrom multi samples'
grep -rnE "^[[:space:]]*copyFrom[[:space:]]*:.*," . | head -12
echo '=== image multi samples'
grep -rnE "^[[:space:]]*image[[:space:]]*:.*," . | head -12
echo '=== shoot_sound samples'
grep -rnE "^[[:space:]]*shoot_sound[[:space:]]*:" . | head -12
