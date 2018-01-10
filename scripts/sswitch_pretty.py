from runtime_CLI import RuntimeAPI, get_parser, thrift_connect, load_json_config, TABLES, TableType, MatchType, BmActionEntryType

class PrettyAPI(RuntimeAPI):
    def __init__(self, pre_type, standard_client, mc_client=None):
        RuntimeAPI.__init__(self, pre_type, standard_client, mc_client)
    prompt = ''
    def dump_action_entry(self, a_entry):
        if a_entry.action_type == BmActionEntryType.NONE:
            print("EMPTY")
        elif a_entry.action_type == BmActionEntryType.ACTION_DATA:
            self.dump_action_and_data(a_entry.action_name, a_entry.action_data)
        elif a_entry.action_type == BmActionEntryType.MBR_HANDLE:
            print("Index: member({})".format(a_entry.mbr_handle))
        elif a_entry.action_type == BmActionEntryType.GRP_HANDLE:
            print("Index: group({})".format(a_entry.grp_handle))

    def dump_one_entry(self, table, entry):
        if table.key:
            out_name_w = max(20, max([len(t[0]) for t in table.key]))

        def hexstr(v):
            return "0x" + "".join("{:02x}".format(ord(c)) for c in v)
        def dump_exact(p):
             return hexstr(p.exact.key)
        def dump_lpm(p):
            return "{}/{}".format(hexstr(p.lpm.key), p.lpm.prefix_length)
        def dump_ternary(p):
            return "{}&&&{}".format(hexstr(p.ternary.key),
                                      hexstr(p.ternary.mask))
        def dump_range(p):
            return "{},{}".format(hexstr(p.range.start),
                                     hexstr(p.range.end_))
        def dump_valid(p):
            return "1" if p.valid.key else "0"
        pdumpers = {"exact": dump_exact, "lpm": dump_lpm,
                    "ternary": dump_ternary, "valid": dump_valid,
                    "range": dump_range}
        lst=[]
        for p, k in zip(entry.match_key, table.key):
            assert(k[1] == p.type)
            pdumper = pdumpers[MatchType.to_str(p.type)]
            lst.append("{0}".format(pdumper(p), w=out_name_w))
        print(' '.join(lst))
        self.dump_action_entry(entry.action_entry)
        if entry.options.priority >= 0:
            print("{}".format(entry.options.priority))

    def dump_action_entry_internal(self, a_entry):
        if a_entry.action_type == BmActionEntryType.NONE:
            return "EMPTY"
        elif a_entry.action_type == BmActionEntryType.ACTION_DATA:
            return self.dump_action_data_internal(a_entry.action_data)
        elif a_entry.action_type == BmActionEntryType.MBR_HANDLE:
            return "Index: member({})".format(a_entry.mbr_handle)
        elif a_entry.action_type == BmActionEntryType.GRP_HANDLE:
            return "Index: group({})".format(a_entry.grp_handle)
    def dump_action_data_internal(self, action_data):
        def hexstr(v):
            return "0x" + "".join("{:02x}".format(ord(c)) for c in v)
        return "{}".format(" ".join([hexstr(a) for a in action_data]))
    def action_name(self, a_entry):
        if a_entry.action_type == BmActionEntryType.NONE:
            return "empty"
        elif a_entry.action_type == BmActionEntryType.ACTION_DATA:
            return a_entry.action_name
        elif a_entry.action_type == BmActionEntryType.MBR_HANDLE:
            return "member({})".format(a_entry.mbr_handle)
        elif a_entry.action_type == BmActionEntryType.GRP_HANDLE:
            return "group({})".format(a_entry.grp_handle)
    def do_table_dump(self, line):
        args = line.split()
        self.exactly_n_args(args, 1)
        table_name = args[0]
        table = self.get_res("table", table_name, TABLES)
        entries = self.client.bm_mt_get_entries(0, table_name)
        for e in entries:
            str='table_add '
            str += table_name + ' '
            str += self.action_name(e.action_entry) + ' '
            def hexstr(v):
                return "0x" + "".join("{:02x}".format(ord(c)) for c in v)
            def dump_exact(p):
                 return hexstr(p.exact.key)
            def dump_lpm(p):
                return "{}/{}".format(hexstr(p.lpm.key), p.lpm.prefix_length)
            def dump_ternary(p):
                return "{}&&&{}".format(hexstr(p.ternary.key),
                                          hexstr(p.ternary.mask))
            def dump_range(p):
                return "{},{}".format(hexstr(p.range.start),
                                         hexstr(p.range.end_))
            def dump_valid(p):
                return "1" if p.valid.key else "0"
            pdumpers = {"exact": dump_exact, "lpm": dump_lpm,
                        "ternary": dump_ternary, "valid": dump_valid,
                        "range": dump_range}
            lst=[]
            for p, k in zip(e.match_key, table.key):
                assert(k[1] == p.type)
                pdumper = pdumpers[MatchType.to_str(p.type)]
                lst.append(pdumper(p))
            str += ' '.join(lst) + ' '
            str += '=> '
            str += self.dump_action_entry_internal(e.action_entry)
            if e.options.priority >= 0:
                if not str.endswith(' '):
                    str += ' '
                str += '{}'.format(e.options.priority)
            print(str)
        default_entry = self.client.bm_mt_get_default_entry(0, table_name)
        print("table_set_default " + table_name + ' ' + self.action_name(default_entry) + self.dump_action_entry_internal(default_entry))

def main():
    args = get_parser().parse_args()
    standard_client, mc_client = thrift_connect(
            args.thrift_ip, args.thrift_port,
            RuntimeAPI.get_thrift_services(args.pre)
        )

    load_json_config(standard_client, args.json)
    PrettyAPI(args.pre, standard_client, mc_client).cmdloop()

if __name__ == "__main__":
    main()
