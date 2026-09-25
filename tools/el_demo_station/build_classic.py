import st
o=[]
for m in ['stage1','stage2','stage3','stage4','stage5','stage6']:
    __import__(m); o+=st.run()
print(len(o)); print(chr(10).join(o[:40]))
