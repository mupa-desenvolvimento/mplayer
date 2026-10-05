import zipfile, struct

with zipfile.ZipFile('scratch/argos_base.apk', 'r') as z:
    dex = z.read('classes7.dex')

string_ids_off = struct.unpack('<I', dex[0x3C:0x40])[0]
type_ids_off = struct.unpack('<I', dex[0x44:0x48])[0]
proto_ids_off = struct.unpack('<I', dex[0x4C:0x50])[0]
method_ids_off = struct.unpack('<I', dex[0x5C:0x60])[0]

def get_str(str_idx):
    off = struct.unpack('<I', dex[string_ids_off + str_idx*4 : string_ids_off + (str_idx+1)*4])[0]
    p = off
    while dex[p] & 0x80: p += 1
    p += 1
    end = dex.find(b'\x00', p)
    return dex[p:end].decode('utf-8', errors='replace')

def get_type_str(type_idx):
    str_idx = struct.unpack('<I', dex[type_ids_off + type_idx*4 : type_ids_off + (type_idx+1)*4])[0]
    return get_str(str_idx)

def get_method_full(mid):
    class_idx, proto_idx, name_idx = struct.unpack('<HHI', dex[method_ids_off + mid*8 : method_ids_off + (mid+1)*8])
    shorty_idx, return_type_idx, parameters_off = struct.unpack('<III', dex[proto_ids_off + proto_idx*12 : proto_ids_off + (proto_idx+1)*12])
    return f'{get_type_str(class_idx)}->{get_str(name_idx)} (returns {get_type_str(return_type_idx)})'

def dump_method(name, code_off):
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack('<HHHHII', dex[code_off:code_off+16])
    print(f'=== Method {name} (code_off {code_off}, regs={registers_size}, ins={ins_size}) ===')
    insns = dex[code_off+16 : code_off+16+insns_size*2]
    p = 0
    while p < len(insns):
        op = insns[p]
        if op == 0x1a:
            reg = insns[p+1]
            s_idx = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  {p:4d}: const-string v{reg}, "{get_str(s_idx)}"')
            p += 4
        elif op == 0x1b:
            reg = insns[p+1]
            s_idx = struct.unpack('<I', insns[p+2:p+6])[0]
            print(f'  {p:4d}: const-string/jumbo v{reg}, "{get_str(s_idx)}"')
            p += 6
        elif op in (0x6e, 0x6f, 0x70, 0x71, 0x72):
            mid = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  {p:4d}: invoke {get_method_full(mid)}')
            p += 6
        elif op in (0x74, 0x75, 0x76, 0x77, 0x78):
            mid = struct.unpack('<H', insns[p+2:p+4])[0]
            print(f'  {p:4d}: invoke/range {get_method_full(mid)}')
            p += 6
        elif op in (0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf): # binop/2addr
            print(f'  {p:4d}: binop/lit8 op=0x{op:02x} {insns[p:p+4].hex()}')
            p += 4
        elif op in (0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19):
            p += 4
        else:
            if op in (0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5):
                print(f'  {p:4d}: binop op=0x{op:02x} {insns[p:p+4].hex()}')
                p += 4
            elif op in (0xb0, 0xb1, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xbb, 0xbc, 0xbd, 0xbe, 0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xcb, 0xcc, 0xcd, 0xce, 0xcf):
                print(f'  {p:4d}: binop/2addr op=0x{op:02x} {insns[p:p+2].hex()}')
                p += 2
            else:
                p += 2

dump_method('challengeNow', 107504)
dump_method('codeFor', 107748)
dump_method('validate', 107020)
