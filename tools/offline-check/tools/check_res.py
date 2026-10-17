"""Cheap re-implementation of the aapt2 checks that most often break an Android build after a resource edit.

run as:  python3 -I check_res.py APP_DIR [VARIANT]        (prints `path:line: error: message`, exit 1 when something is wrong)

Checks (all taken from aapt2 behaviour; this is NOT a full resource compiler):
  * every res/**/*.xml and AndroidManifest.xml is well-formed XML
  * duplicate resource definitions (same type + name + config qualifier) -- across files too
  * values/*.xml string, string-array, plurals items: unescaped apostrophe, bad \\u escapes, and several non-positional format
    substitutions in one string (e.g. "%s of %s") unless formatted="false"
  * @type/name references (values, res/xml, res/drawable, res/mipmap-*, manifest) that point to a resource that does not exist
    in the app (framework `@android:` and `?attr` references and `@+id` definitions are not checked); the implicit parent of
    a dotted <style> name must exist
"""
import os
import re
import sys
from xml.parsers import expat

TYPE_OF_TAG = {
    "string": "string", "string-array": "array", "array": "array", "integer-array": "array", "plurals": "plurals",
    "color": "color", "dimen": "dimen", "bool": "bool", "integer": "integer", "style": "style", "fraction": "fraction",
    "drawable": "drawable", "attr": "attr",
}
DIR_TYPES = {"drawable", "mipmap", "xml", "raw", "font", "layout", "anim", "animator", "menu", "navigation", "color",
             "interpolator", "transition"}
REF = re.compile(r"@(?!android:|\*android:|\+|null\b|empty\b|undefined\b)([a-z]+)/([A-Za-z0-9_.]+)")
SEEN_ERRORS = []


class Node:
    __slots__ = ("tag", "attrs", "line", "children", "text", "parent")

    def __init__(self, tag, attrs, line, parent):
        self.tag, self.attrs, self.line, self.parent = tag, attrs, line, parent
        self.children, self.text = [], []

    def all_text(self):
        out = "".join(self.text)
        for c in self.children:
            out += c.all_text()
        return out


def parse(path):
    """Tiny expat tree builder that keeps line numbers."""
    p = expat.ParserCreate()
    root, stack = [], []
    def start(name, attrs):
        node = Node(name, dict(attrs), p.CurrentLineNumber, stack[-1] if stack else None)
        (stack[-1].children if stack else root).append(node)
        stack.append(node)
    def end(name):
        stack.pop()
    def chars(data):
        if stack:
            stack[-1].text.append(data)
    p.StartElementHandler, p.EndElementHandler, p.CharacterDataHandler = start, end, chars
    with open(path, "rb") as fh:
        p.Parse(fh.read(), True)
    return root[0] if root else None


def err(rel, line, msg):
    SEEN_ERRORS.append((rel, line, msg))


def norm(name):
    return name.replace(".", "_")


def check_string_text(rel, node, text):
    """aapt2 StringBuilder + VerifyJavaStringFormat."""
    quoted = False
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == "\\":
            i += 1
            if i < len(text) and text[i] == "u":
                hexd = text[i + 1:i + 5]
                if len(hexd) < 4 or not re.fullmatch(r"[0-9A-Fa-f]{4}", hexd):
                    err(rel, node.line, f"invalid unicode escape sequence in string '{node.attrs.get('name')}'")
                    return
                i += 4
        elif ch == '"':
            quoted = not quoted
        elif ch == "'" and not quoted:
            err(rel, node.line, f"unescaped apostrophe in string '{node.attrs.get('name')}' (use \\' or wrap the text in double quotes)")
            return
        i += 1
    if node.attrs.get("formatted") == "false":
        return
    # VerifyJavaStringFormat
    args, nonpositional, j = 0, False, 0
    while j < len(text):
        if text[j] == "%" and j + 1 < len(text):
            j += 1
            if text[j] == "%":
                j += 1
                continue
            args += 1
            m = re.match(r"\d+", text[j:])
            if m:
                j += m.end()
                if j < len(text) and text[j] != "$":
                    nonpositional = True
            elif text[j] == "<":
                nonpositional = True
                j += 1
                if j < len(text) and text[j] == "$":
                    j += 1
            else:
                nonpositional = True
            while j < len(text) and (text[j] in "-#+ ,(" or text[j].isdigit()):
                j += 1
            if j < len(text):
                j += 1
        else:
            j += 1
    if nonpositional and args > 1:
        err(rel, node.line, f"multiple substitutions specified in non-positional format of string '{node.attrs.get('name')}' "
                            f"(number them: %1$s %2$s, escape a literal percent as %%, or add formatted=\"false\")")


def main():
    app, variant = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "debug")
    repo = os.path.dirname(os.path.abspath(app))
    res_dirs = [os.path.join(app, "src", s, "res") for s in ("main", variant)]
    manifests = [os.path.join(app, "src", s, "AndroidManifest.xml") for s in ("main", variant)]
    defined = {}          # (type, name) -> True
    defined_at = {}       # (type, name, config) -> (rel, line)
    docs = []             # (rel, root, kind)

    def rel(p):
        return os.path.relpath(p, repo)

    for res in res_dirs:
        if not os.path.isdir(res):
            continue
        for entry in sorted(os.listdir(res)):
            d = os.path.join(res, entry)
            if not os.path.isdir(d):
                continue
            base, _, config = entry.partition("-")
            for f in sorted(os.listdir(d)):
                path = os.path.join(d, f)
                if not f.endswith(".xml") and base == "values":
                    continue
                try:
                    root = parse(path) if f.endswith(".xml") else None
                except expat.ExpatError as e:
                    err(rel(path), getattr(e, "lineno", 1), f"XML is not well-formed: {e}")
                    continue
                if base == "values":
                    docs.append((rel(path), root, "values"))
                    for el in root.children:
                        name = el.attrs.get("name")
                        t = el.tag
                        if t == "item" and el.attrs.get("type"):
                            t = el.attrs["type"]
                        elif t in TYPE_OF_TAG:
                            t = TYPE_OF_TAG[t]
                        elif t == "declare-styleable":
                            for a in el.children:
                                if a.tag == "attr" and a.attrs.get("name") and ":" not in a.attrs["name"] and a.attrs.get("format"):
                                    defined[("attr", norm(a.attrs["name"]))] = True
                            continue
                        else:
                            continue
                        if not name:
                            continue
                        key = (t, norm(name))
                        defined[key] = True
                        ckey = key + (config,)
                        if ckey in defined_at and not (t == "attr"):
                            prev = defined_at[ckey]
                            err(rel(path), el.line, f"duplicate value for resource '{t}/{name}' with config '{config}' "
                                                    f"(also defined at {prev[0]}:{prev[1]})")
                        else:
                            defined_at[ckey] = (rel(path), el.line)
                else:
                    stem = f.split(".")[0]
                    if base in DIR_TYPES and stem:
                        key = (base, norm(stem))
                        defined[key] = True
                        ckey = key + (config,)
                        if ckey in defined_at:
                            prev = defined_at[ckey]
                            err(rel(path), 1, f"duplicate value for resource '{base}/{stem}' with config '{config}' "
                                              f"(also defined at {prev[0]})")
                        else:
                            defined_at[ckey] = (rel(path), 1)
                    if root is not None:
                        docs.append((rel(path), root, base))
                        # ids defined inline
                        def walk(n):
                            for v in n.attrs.values():
                                m = re.match(r"@\+id/([A-Za-z0-9_.]+)", v)
                                if m:
                                    defined[("id", norm(m.group(1)))] = True
                            for c in n.children:
                                walk(c)
                        walk(root)
    for m in manifests:
        if os.path.exists(m):
            try:
                docs.append((rel(m), parse(m), "manifest"))
            except expat.ExpatError as e:
                err(rel(m), getattr(e, "lineno", 1), f"XML is not well-formed: {e}")

    # string content checks + references
    for path, root, kind in docs:
        if root is None:
            continue
        if kind == "values":
            for el in root.children:
                if el.tag == "string":
                    check_string_text(path, el, el.all_text())
                elif el.tag in ("string-array", "plurals"):
                    for it in el.children:
                        if it.tag == "item":
                            check_string_text(path, it, it.all_text())
                if el.tag == "style":
                    name = el.attrs.get("name", "")
                    parent = el.attrs.get("parent")
                    if parent is None and "." in name:
                        implicit = norm(name.rsplit(".", 1)[0])
                        if ("style", implicit) not in defined:
                            err(path, el.line, f"resource style/{name.rsplit('.', 1)[0]} (implicit parent of style/{name}) not found")
                    elif parent and not parent.startswith(("android:", "@android:", "@*android:")) and parent != "":
                        p = parent[len("@style/"):] if parent.startswith("@style/") else parent
                        if ("style", norm(p)) not in defined and ":" not in p:
                            err(path, el.line, f"resource style/{p} (parent of style/{name}) not found")
        # @type/name references anywhere
        def walk(n):
            texts = list(n.attrs.values()) + ["".join(n.text).strip()]
            for v in texts:
                for m in REF.finditer(v):
                    t, name = m.group(1), m.group(2)
                    if t in ("string", "color", "dimen", "style", "drawable", "mipmap", "xml", "raw", "font", "layout", "bool", "integer",
                             "array", "plurals", "anim", "menu", "id", "fraction") and (t, norm(name)) not in defined:
                        # drawables may also be colors
                        if t == "drawable" and ("color", norm(name)) in defined:
                            continue
                        err(path, n.line, f"resource {t}/{name} not found")
            for c in n.children:
                walk(c)
        walk(root)

    # manifest components that live in the app must exist as classes
    ns_match = re.search(r'namespace\s*=\s*"([^"]+)"', open(os.path.join(app, "build.gradle.kts"), encoding="utf-8").read())
    ns = ns_match.group(1) if ns_match else None
    if ns:
        sources = {}
        for root_dir in (os.path.join(app, "src", "main"), os.path.join(app, "src", variant)):
            for dp, _dn, fns in os.walk(root_dir):
                for fn in fns:
                    if fn.endswith((".kt", ".java")):
                        sources[os.path.join(dp, fn)] = None
        def declared(fqn):
            pkg, _, cls = fqn.rpartition(".")
            decl = re.compile(r"\b(class|object|interface)\s+" + re.escape(cls) + r"\b")
            for fpath in sources:
                if os.path.basename(fpath).split(".")[0] != cls and not fpath.endswith(".kt"):
                    continue
                if sources[fpath] is None:
                    sources[fpath] = open(fpath, encoding="utf-8", errors="replace").read()
                text = sources[fpath]
                if re.search(r"^package\s+" + re.escape(pkg) + r"\s*$", text, re.M) and decl.search(text):
                    return True
            return False
        for path, root, kind in docs:
            if kind != "manifest" or root is None:
                continue
            def walk(n):
                if n.tag in ("application", "activity", "activity-alias", "service", "receiver", "provider"):
                    name = n.attrs.get("android:name")
                    if name:
                        fqn = ns + name if name.startswith(".") else name
                        if fqn.startswith(ns + ".") and not declared(fqn):
                            err(path, n.line, f"<{n.tag} android:name=\"{name}\"> refers to class {fqn}, which is not declared in the sources")
                for c in n.children:
                    walk(c)
            walk(root)

    seen = set()
    for rel_path, line, msg in sorted(SEEN_ERRORS):
        if (rel_path, line, msg) in seen:
            continue
        seen.add((rel_path, line, msg))
        print(f"{rel_path}:{line}: error: {msg}")
    print(f"resource checks: {len(seen)} error(s)")
    sys.exit(1 if seen else 0)


main()
