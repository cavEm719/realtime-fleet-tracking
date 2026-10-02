"""Offline UTC timestamp diagnostic, separate from required-fact task success.

Only explicit full dates plus times are checked. Each mentioned minute must match
an instant returned by a tool or an expected query boundary. Context association
and omitted/implicit dates remain outside this conservative diagnostic.
"""
import json
import re
from datetime import datetime,timezone
from graders import normalize
from failure_analysis import tool_calls
from fixture import epoch,NOW


def audit(row):
    allowed={epoch(NOW)//60000}
    data_instants=set()
    collecting_data=False
    def collect(value):
        if isinstance(value,dict):
            for key,item in value.items():
                if isinstance(item,(int,float)) and (key.endswith('Ts') or key.endswith('_ts') or key in ('emittedAt','ingestedAt')):
                    allowed.add(int(item)//60000)
                    if collecting_data: data_instants.add(int(item)//60000)
                elif isinstance(item,str) and (key.endswith('Utc') or key.endswith('_utc')):
                    try:allowed.add(epoch(item)//60000)
                    except ValueError:pass
                collect(item)
        elif isinstance(value,list):
            for item in value:collect(item)
    for call in tool_calls(row):
        for field in ('result','arguments'):
            collecting_data = field=='result'
            try:collect(json.loads(call.get(field,'')))
            except (ValueError,TypeError):pass
    collecting_data=False
    for field in ('expectedArguments','query'):
        collect(row.get('ground_truth',{}).get(field,{}))
    text=normalize(row.get('answer',''))
    mentions=[]
    for m in re.finditer(r'(\d{4}-\d{2}-\d{2})[t\s]+(\d{1,2}:\d{2})(?::\d{2}(?:\.\d+)?)?',text):
        try:mentions.append((m[0],epoch(m[1]+'T'+m[2].zfill(5)+':00Z')//60000,m.start(),m.end()))
        except ValueError:pass
    for m in re.finditer(r'([a-z]+) (\d{1,2}),? (\d{4})(?:,? at|,? around|,)? (\d{1,2}:\d{2})(?::\d{2})?',text):
        for format_ in ('%B %d %Y %H:%M','%b %d %Y %H:%M'):
            try:
                dt=datetime.strptime(' '.join(m.groups()),format_).replace(tzinfo=timezone.utc)
                mentions.append((m[0],int(dt.timestamp())//60,m.start(),m.end()));break
            except ValueError:pass
    for m in re.finditer(r'(\d{4})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2}:\d{2})(?::\d{2})?',text):
        try:
            instant=epoch(f'{int(m[1]):04d}-{int(m[2]):02d}-{int(m[3]):02d}T{m[4].zfill(5)}:00Z')//60000
            mentions.append((m[0],instant,m.start(),m.end()))
        except ValueError:pass
    wrong=[]
    for mention,minute,start,end in mentions:
        near=text[max(0,start-170):min(len(text),end+90)]
        source_numbers=re.findall(r'(?<!\d)(1\d{12})(?!\d)',near)
        prefix='\n'.join(text[:start].splitlines()[-3:])
        is_data_claim=bool(re.search(r'entered at|arrived at|arrival time|event time|事件时间|进入(?:港口)?(?:的)?时间|last.{0,30}transition|last exited|confirmed.{0,50}inside.{0,50}at',prefix))
        if source_numbers:
            valid=any(minute==int(number)//60000 for number in source_numbers)
        elif is_data_claim and data_instants:
            valid=minute in data_instants
        else:
            valid=minute in allowed
        if not valid:wrong.append(mention)
    wrong=sorted(set(wrong))
    return {'wrongTimestampDetected':bool(wrong),'unmatchedUtcMentions':wrong,'checkedMentions':len(mentions),
            'method':'explicit full date/time; minute precision; nearby epochs and basic data-claim context; otherwise returned instant/query boundary; implicit dates and exact entity association unverified'}
