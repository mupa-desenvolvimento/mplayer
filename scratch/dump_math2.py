import zipfile, struct

with zipfile.ZipFile('scratch/argos_base.apk', 'r') as z:
    dex = z.read('classes7.dex')

string_ids_off = struct.unpack('<I', dex[0x3C:0x40])[0]
def get_str(str_idx):
    off = struct.unpack('<I', dex[string_ids_off + str_idx*4 : string_ids_off + (str_idx+1)*4])[0]
    p = off
    while dex[p] & 0x80: p += 1
    p += 1
    end = dex.find(b'\x00', p)
    return dex[p:end].decode('utf-8', errors='replace')

for off, name in [(107748, 'codeFor'), (107020, 'validate'), (107236, 'MINUTES')]:
    regs, ins, outs, tries, debug, insns_size = struct.unpack('<HHHHII', dex[off:off+16])
    insns = dex[off+16 : off+16+insns_size*2]
    print(f'=== {name} (off={off}) ===')
    p = 0
    while p < len(insns):
        op = insns[p]
        if op == 0x1a:
            reg = insns[p+1]
            s_idx = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  {p}: const-string v{reg}, "{get_str(s_idx)}"')
            p += 4
        elif op == 0x1b:
            reg = insns[p+1]
            s_idx = struct.unpack('<I', insns[p+2:p+6])[0]
            print(f'  {p}: const-string/jumbo v{reg}, "{get_str(s_idx)}"')
            p += 6
        elif op in (0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19):
            p += 4
        elif op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
            p += 6
        elif op in (0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf):
            p += 4
        elif op in (0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5):
            p += 4
        else:
            p += 2
