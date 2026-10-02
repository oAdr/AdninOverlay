"""Owned SRG fixtures and committed Forge mapping integrity checks."""
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from forge_mapping import compose_mapping, descriptor, parse_srg


class RuntimeFixture:
    def get(self, name):
        return {'p': {'parent': 'java/lang/Object', 'interfaces': [],
                      'fields': [{'name': 'x', 'descriptor': 'Lq;'}]},
                'q': {'parent': 'p', 'interfaces': [], 'fields': []}}.get(name)


class ForgeMappingTests(unittest.TestCase):
    def setUp(self):
        self.text = '\n'.join([
            'PK: . net/minecraft/src',
            'CL: p net/minecraft/fixture/Parent',
            'CL: q net/minecraft/fixture/Child',
            'FD: p/x net/minecraft/fixture/Parent/field_1_x',
            'MD: p/a (Lq;)Lp; net/minecraft/fixture/Parent/func_1_a '
            '(Lnet/minecraft/fixture/Child;)Lnet/minecraft/fixture/Parent;',
            'MD: p/a (I)I net/minecraft/fixture/Parent/func_2_a (I)I'])
        self.compat = {'classes': {'net/minecraft/fixture/Parent': 'p',
                                   'net/minecraft/fixture/Child': 'q'},
                       'fields': [['net/minecraft/fixture/Parent', 'child',
                                   'Lnet/minecraft/fixture/Child;', 'x']],
                       'methods': [['net/minecraft/fixture/Parent', 'accept',
                                    '(Lnet/minecraft/fixture/Child;)Lnet/minecraft/fixture/Parent;', 'a'],
                                   ['net/minecraft/fixture/Parent', 'number', '(I)I', 'a']]}

    def test_composition_preserves_owner_overload_and_descriptor(self):
        mapping = compose_mapping(self.compat, parse_srg(self.text), RuntimeFixture())
        self.assertEqual(mapping['fields'][0][-1], 'field_1_x')
        self.assertEqual([row[-1] for row in mapping['methods']], ['func_1_a', 'func_2_a'])
        self.assertEqual(mapping['classes']['net/minecraft/fixture/Child'], 'net/minecraft/fixture/Child')
        self.assertIn(['p', 'x', 'Lq;', 'net/minecraft/fixture/Parent',
                       'field_1_x', 'Lnet/minecraft/fixture/Child;'], mapping['nativeMapping']['fields'])
        self.assertEqual(mapping['nativeMapping']['parents']['q'], ['p'])

    def test_array_and_primitive_descriptor(self):
        self.assertEqual(descriptor('([Lp;I)[[Lq;', {'p': 'P', 'q': 'Q'}), '([LP;I)[[LQ;')

    def test_invalid_rows_conflicts_and_owner_fail_closed(self):
        for text in ('CL: p', 'UNKNOWN: p q', self.text + '\nCL: p Different',
                     self.text.replace('Parent/field_1_x', 'Child/field_1_x'),
                     self.text.replace('func_2_a (I)I', 'func_2_a (J)I')):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_srg(text)

    def test_missing_member_is_not_silently_left_as_mcp(self):
        self.compat['methods'][0][-1] = 'absent'
        with self.assertRaisesRegex(ValueError, 'Forge member mapping missing'):
            compose_mapping(self.compat, parse_srg(self.text), RuntimeFixture())

    def test_server_only_field_is_recorded_without_fabricated_descriptor(self):
        self.compat['fields'] = []
        mapping = compose_mapping(self.compat, parse_srg(self.text.replace('p/x', 'p/y')), RuntimeFixture())
        self.assertEqual(mapping['nativeMapping']['absentClientFields'], ['p/y'])
        self.assertEqual(mapping['nativeMapping']['fields'], [])

    def test_shared_field_descriptor_is_checked_against_runtime(self):
        self.compat['fields'][0][2] = 'I'
        with self.assertRaisesRegex(ValueError, 'Shared field descriptor missing'):
            compose_mapping(self.compat, parse_srg(self.text), RuntimeFixture())

    def test_committed_resource_has_exact_build_mapping_provenance(self):
        resource = ROOT / 'resources/java-forge-1.8.9.json'
        mapping = json.loads(resource.read_text(encoding='utf-8'))
        compat = ROOT / 'resources/java-compat-1.8.9.json'
        self.assertEqual(mapping['provenance']['compatibilityMappingSha256'],
                         hashlib.sha256(compat.read_bytes()).hexdigest())
        self.assertEqual(mapping['nativeMapping']['classes']['ave'], 'net/minecraft/client/Minecraft')
        self.assertIn(['net/minecraft/client/Minecraft', 'getMinecraft',
                       '()Lnet/minecraft/client/Minecraft;', 'func_71410_x'], mapping['methods'])
        self.assertIn(['net/minecraft/client/Minecraft', 'ingameGUI',
                       'Lnet/minecraft/client/gui/GuiIngame;', 'field_71456_v'], mapping['fields'])
        self.assertIn(['ave', 'A', '()Lave;', 'net/minecraft/client/Minecraft',
                       'func_71410_x', '()Lnet/minecraft/client/Minecraft;'], mapping['nativeMapping']['methods'])
        native_fields = mapping['nativeMapping']['fields']
        self.assertIn(['ave', 'q', 'Lavo;', 'net/minecraft/client/Minecraft',
                       'field_71456_v', 'Lnet/minecraft/client/gui/GuiIngame;'], native_fields)
        encoded = resource.read_text(encoding='utf-8')
        self.assertNotIn('C:', encoded)
        self.assertNotIn('api_key', encoded.lower())
        self.assertNotIn('http://', encoded)
        self.assertNotIn('https://', encoded)
        self.assertEqual(len({tuple(row[:3]) for row in native_fields}), len(native_fields))
        self.assertEqual(len({tuple(row[:3]) for row in mapping['nativeMapping']['methods']}),
                         len(mapping['nativeMapping']['methods']))


if __name__ == '__main__':
    unittest.main()
