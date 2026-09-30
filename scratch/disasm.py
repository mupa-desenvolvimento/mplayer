import zipfile, struct

with zipfile.ZipFile('scratch/argos_base.apk', 'r') as z:
    dex = z.read('classes7.dex')

string_ids_off = struct.unpack('<I', dex[0x3C:0x40])[0]
method_ids_off = struct.unpack('<I', dex[0x54:0x58])[0]

def get_str(str_idx):
    off = struct.unpack('<I', dex[string_ids_off + str_idx*4 : string_ids_off + (str_idx+1)*4])[0]
    p = off
    while dex[p] & 0x80: p += 1
    p += 1
    end = dex.find(b'\x00', p)
    return dex[p:end].decode('utf-8', errors='replace')

def get_method_name(mid):
    class_idx, proto_idx, name_idx = struct.unpack('<HHI', dex[method_ids_off + mid*8 : method_ids_off + (mid+1)*8])
    return get_str(name_idx)

code_offs = [107748, 107236, 107288, 107504, 107020]
for code_off in code_offs:
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', dex[code_off:code_off+16])
    print(f'=== code_off {code_off}: regs={registers_size}, ins={ins_size}, outs={outs_size}, insns_size={insns_size} ===')
    insns = dex[code_off+16 : code_off+16+insns_size*2]
    p = 0
    while p < len(insns):
        op = insns[p]
        if op == 0x1a: # const-string
            reg = insns[p+1]
            str_idx = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  [{p}] const-string v{reg}, "{get_str(str_idx)}"')
            p += 4
        elif op == 0x1b: # const-string/jumbo
            reg = insns[p+1]
            str_idx = struct.unpack('<I', insns[p+2:p+6])[0]
            print(f'  [{p}] const-string/jumbo v{reg}, "{get_str(str_idx)}"')
            p += 6
        elif op in (0x6e, 0x6f, 0x70, 0x71, 0x72): # invoke-kind
            mid = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  [{p}] invoke {get_method_name(mid)}')
            p += 6
        elif op in (0x74, 0x75, 0x76, 0x77, 0x78): # invoke-kind/range
            mid = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  [{p}] invoke/range {get_method_name(mid)}')
            p += 6
        elif op in (0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19):
            p += 4 # 32-bit const
        elif op in (0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf):
            p += 4
        else:
            p += 2
