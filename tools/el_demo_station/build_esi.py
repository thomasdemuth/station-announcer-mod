import st
st.DZ=50
st.COURSES={'platform':['esi_wall_glass','esi_wall_glass','esi_wall_glass'],'open':['esi_wall_mesh','esi_wall_mesh'],
            'mezz':['esi_wall_steel','esi_wall_art_glass','esi_wall_steel'],'door':['esi_wall_doorway','esi_wall_doorway','esi_wall_glass']}
st.BMAP={b:'esi'+b[2:] for b in ['el_street_column','el_girder_plate','el_track_deck','el_plate_deck','el_post','el_roof',
  'el_roof_light','el_platform_lamp','el_platform_lamp_head','el_mezzanine_floor','el_ceiling','el_ceiling_light',
  'el_stair_railing','el_stair_wall','el_stair_wall_glass','el_stair_open','el_stair_upper','el_stair_roof',
  'el_landing_roof','el_stair_landing']}
st.BMAP['el_railing']='esi_railing'
o=[]
import stage1; o+=st.run()
import stage2; o+=st.run()
import stage3; o+=st.run()
import stage4; o+=st.run()
import stage5; o+=st.run()
import stage6; o+=st.run()
print(len(o)); print(chr(10).join(o[:30]))
