import os
import re
import concurrent.futures
from tqdm import tqdm
import argparse

this_dir = os.path.dirname(os.path.abspath(__file__))
cmd = "sed -i -E -f {} {}"
assertion_pattern = r"if\s* \(\s*`ASSERT_VERBOSE_COND_\s*\)\s*\$error\(\"Assertion failed([\x00-\x7f]*?)if\s*\(\s*`STOP_COND_\)"
replace_pattern = '$fwrite(32\'h80000002, "Assertion failed: %m @ %t ", $time); $fwrite(32\'h80000002,"\\1if (`STOP_COND_)'

class RtlFileWorker:
    vcs_style:bool = True
    scr:str = ""

    def run(self):
        run_cmd = cmd.format(self.scr, self.file)
        os.system(run_cmd)
        new_text:str = ""
        with open(self.file, "r") as f:
            file_str = f.read()
            new_text = re.sub(assertion_pattern, replace_pattern, file_str)
        
        with open(self.file, "w") as f:
            f.write(new_text)

    def __init__(self, file:str, vcs:bool):
        self.file = file
        if(vcs):
            self.scr = os.path.join(this_dir, "vcs.sed")
        else:
            self.scr = os.path.join(this_dir, "verilator.sed")

def post_compile(rtl_dir:str, vcs:bool, jobs:int):
    worker_list = []
    for filename in os.listdir(rtl_dir):
        if filename.endswith(".sv"):
            worker_list.append(RtlFileWorker(os.path.join(rtl_dir, filename), vcs))

        if filename.endswith(".v"):
            old_file = os.path.join(rtl_dir, filename)
            new_filename = filename.replace('.v', '.sv')
            new_file = os.path.join(rtl_dir, new_filename)
            os.rename(old_file, new_file)
            worker_list.append(RtlFileWorker(new_file, vcs))

    print("Doing post-compiling procedures!")
    with concurrent.futures.ThreadPoolExecutor(jobs) as executor:
        results = concurrent.futures.as_completed([executor.submit(lambda x: x.run(), w) for w in worker_list])
        list(tqdm(results, total=len(worker_list)))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Post Compilation Script for XS')
    parser.add_argument('rtl_dir', type=str, help='Build diretory')
    parser.add_argument('--vcs', action='store_true', help='VCS style assertion')
    parser.add_argument('-j', '--jobs', default=16, type=int, help='Parallel jobs', metavar='')
    args = parser.parse_args()
    curdir = os.path.abspath(os.getcwd())
    rtl_dir = os.path.join(curdir, args.rtl_dir)
    post_compile(rtl_dir, args.vcs, args.jobs)
