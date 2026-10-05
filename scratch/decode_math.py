# Let's inspect the exact dalvik opcodes in python:
DALVIK_OPS = {
    0x17: "const-wide/high16",
    0x16: "const/16",
    0x81: "int-to-long",
    0xbb: "add-long/2addr",
    0xbd: "mul-long/2addr",
    0x9a: "rem-long",
    0x9f: "rem-int",
    0xd8: "add-int/lit8",
    0x28: "goto",
}

import zipfile, struct

with zipfile.ZipFile('scratch/argos_base.apk', 'r') as z:
    dex = z.read('classes7.dex')

code_off = 107504
insns = dex[code_off+16 : code_off+16+67*2]

# Let's decode bytes from 70 to 114
# 70: invoke charAt -> result in v5
# 74: const/16 v6, 131
# 78: int-to-long v6, v6
# 80: mul-long/2addr v1, v6
# 82: int-to-long v8, v5
# 84: add-long/2addr v1, v8
# 86: 17 08 07 ca -> const-wide/high16 v8, 0xca07... wait, let's see what value:
high_val = struct.unpack('<h', insns[88:90])[0]
print(f"high_val: 0x{insns[88:90].hex()} = {high_val}")
# 90: 9f 01 06 08 -> wait, rem-long v1, v1, v8?
# Let's check 90: 9a 3b 9f 01 06 08 ->
print("bytes 86-102:", insns[86:102].hex())
