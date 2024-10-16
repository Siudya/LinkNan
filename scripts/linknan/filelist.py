import re
import os
from concurrent.futures import ProcessPoolExecutor, as_completed

module_instance_re = r"\s*(\w+)\s+\w+\s+\([.\s\w(),\/\*]*;"
module_definition_re = r"\s*module\s*\w+"
macros_re_list = [r"\w*ClockGate$", r"\w*sram_array_[12]p\d+x\w*", r"\w*ClockManagerWrapper$"]

def is_macros(line:str):
    results = [re.match(pattern, line) for pattern in macros_re_list]
    return any(x is not None for x in results)

class ModuleParser:

    module_name:str
    submodule_set: set[str]
    ignore_module_list: list[str]
    rtl_str_dict:dict[str, str]
    export_file:str

    def __init__(self, module_name:str, rtl_pool:dict[str, str], ignore_list:list[str], export_dir:str):
        self.module_name = module_name
        self.rtl_str_dict = rtl_pool
        self.ignore_module_list = ignore_list
        self.submodule_set = set()
        self.submodule_set.add(module_name)
        self.export_dir = export_dir

    def run(self):
        self.parse(self.module_name)
        with open(f"{self.export_dir}/{self.module_name}.f", mode = "w", encoding='utf-8') as f:
            for s in self.submodule_set:
                if is_macros(s):
                    f.write(f"$release_path/macros/{s}.sv\n")
                else:
                    f.write(f"$release_path/rtl/{s}.sv\n")



    def parse(self, module:str):
        module_content = self.rtl_str_dict[module]
        matches = re.findall(module_instance_re, module_content)
        for m in matches:
            if m not in self.submodule_set:
                if m not in self.ignore_module_list:
                    if m != "module" and m in self.rtl_str_dict:
                        self.submodule_set.add(m)
                        self.parse(m)

def parse_children(src_dir:str, dst_dir:str, harden_list:list[str]):
    if not os.path.exists(dst_dir):
        os.makedirs(dst_dir)
    rtl_pool = dict[str, str]()
    for filename in os.listdir(src_dir):
        if filename.endswith(".v"):
            old_file = os.path.join(src_dir, filename)
            new_filename = filename.replace('.v', '.sv')
            new_file = os.path.join(src_dir, new_filename)
            os.rename(old_file, new_file)

    for filename in os.listdir(src_dir):
        if filename.endswith(".sv"):
            full_fn = os.path.join(src_dir, filename)
            with open(full_fn, mode = "r") as f:
                bn, _ = os.path.splitext(filename)
                rtl_pool[bn] = f.read()

    parser_list = list(map(lambda x: ModuleParser(x, rtl_pool, harden_list, dst_dir), harden_list))
    with ProcessPoolExecutor(max_workers = len(harden_list)) as executor:
        results = [executor.submit(p.run) for p in parser_list]