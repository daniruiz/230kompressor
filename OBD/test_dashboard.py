import io
import unittest
from rich.console import Console
import mercedes_dashboard as d

REPLY='6A0100197017DCFF0403020100000000\r>'

class DashboardTests(unittest.TestCase):
    def test_decoding_width_and_endianness(self):
        s=d.grouped(REPLY)
        self.assertEqual((s.rpm,s.injection,s.load),(6400,6000,220))
        self.assertEqual(s.corrections,(4,3,2,1,0,0,0,0))

    def test_invalid_frames(self):
        for text in ('NO DATA','7F2A31','6A010019','6A0100197017DCFF040302010000000000',REPLY+REPLY):
            with self.subTest(text=text), self.assertRaises(d.DiagnosticError):
                d.grouped(text)

    def test_configuration_matches_handler(self):
        # Handler checks 809E=01, 809F=D2/D0, 80A0=1..12,
        # 80A1=11, 80A2!=0; also when clearing.
        self.assertEqual(d.definition(1,4),'2C01D2011104')
        self.assertEqual(d.definition(12,4,True),'2C01D00C1104')
        with self.assertRaises(ValueError): d.definition(0,4)
        with self.assertRaises(ValueError): d.definition(12,0,True)
        class Fake:
            def __init__(self): self.calls=[]
            def query(self,cmd,optimized=True):
                self.calls.append((cmd,optimized))
                if cmd=='2A0101': return REPLY
                return '6C01'+cmd[4:6]
        elm=Fake()
        self.assertEqual(d.ELM.define_group(elm).rpm,6400)
        self.assertEqual(len(elm.calls),13)
        self.assertTrue(all(not fast for _,fast in elm.calls))
        self.assertEqual(elm.calls[6][0],'2C01D2071131')
        self.assertEqual(elm.calls[7][0],'2C01D0081104')

    def test_alarm_and_gaps(self):
        m=d.Model()
        def s(t,n): return d.Sample(2000,4000,150,(n,)*8,125,t)
        self.assertFalse(m.accept(s(10,4)))
        self.assertTrue(m.accept(s(10.2,8)))
        self.assertEqual((m.count,m.alert_until),(1,13.2))
        self.assertFalse(m.accept(s(10.4,8)))
        self.assertFalse(m.accept(s(10.6,4)))
        self.assertTrue(m.accept(s(10.8,5)))
        self.assertEqual(m.alert_until,13.8)
        self.assertFalse(m.accept(s(14,9)))
        m.reject('timeout')
        self.assertFalse(m.accept(s(14.2,10)))
        self.assertEqual(m.count,2)

    def test_shortcut_fallback(self):
        class Fake:
            fast=True
            def __init__(self): self.calls=[]
            def command(self,cmd,**kw):
                self.calls.append(cmd)
                return '?' if cmd.endswith(' 1') else REPLY
        elm=Fake()
        self.assertEqual(d.ELM.query(elm,'2A0101'),REPLY)
        self.assertFalse(elm.fast)
        self.assertEqual(elm.calls,['2A0101 1','2A0101'])

    def test_render_and_stale(self):
        args=d.parse_args(['--demo'])
        m=d.Model(); m.accept(d.grouped(REPLY)); m.mode='DEMO'
        for width,height in ((110,36),(100,30)):
            c=Console(file=io.StringIO(),width=width,height=height,record=True)
            c.print(d.render(m,args,width,height,now=m.sample.at))
            text=c.export_text()
            self.assertIn('6 400',text)
            self.assertIn('16.00',text)
            self.assertIn('Ctrl+C',text)
        c=Console(file=io.StringIO(),width=110,height=36,record=True)
        c.print(d.render(m,args,110,36,now=m.sample.at+3))
        self.assertIn('NO SE PUEDE EVALUAR',c.export_text())

if __name__=='__main__': unittest.main()
