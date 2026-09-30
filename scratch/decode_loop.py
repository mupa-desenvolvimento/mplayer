# Let's decode challengeNow instructions:
# 20: const-string v1, "ARGOS-MNT-v1|"
# 24: append("ARGOS-MNT-v1|")
# 32: append(challenge) -> "ARGOS-MNT-v1|dd/MM/yyyy HH:mm"
# 40: toString() -> s
# 52: s.length() -> len
# loop i from 0 to len-1:
#   c = s.charAt(i)
#   74: const/16 v6, 131
#   80: v1 = v1 * 131 + c
#   86: const/high16 v8, 0x7ca0? Let's check 86: 170807ca9a3b
# let's disassemble 70-110 in hex precisely

import zipfile, struct

with zipfile.ZipFile('scratch/argos_base.apk', 'r') as z:
    dex = z.read('classes7.dex')

code_off = 107504
insns = dex[code_off+16 : code_off+16+67*2]

# Let's print each instruction with its address
for i in range(70, 114, 2):
    print(f'{i}: {insns[i:i+2].hex()}')
