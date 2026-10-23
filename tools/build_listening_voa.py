# -*- coding: utf-8 -*-
"""生成 v3.0 新增的 5 篇 VOA 听力素材（l-005 ~ l-009）的 LRC 字幕，
并把条目追加进听力 manifest。

音频来源：cuongdq88/voa（GitHub 上的 VOA 全量音频存档，VOA 为美国政府作品、公共域）。
字幕：ASR 转录 + 人工校对（带时间戳，可直接生成 LRC）。
用法：python build_listening_voa.py
"""
import json
import os
import re

_TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(_TOOLS)
WS = os.path.dirname(PROJ)

ART_DIR = os.path.join(WS, "repo-v2.0", "articles")          # LRC 落这里（仓库）
APK_DIR = os.path.join(PROJ, "app", "src", "main", "assets", "listening")
MANIFEST_REPO = os.path.join(WS, "repo-v2.0", "listening.json")
MANIFEST_APK = os.path.join(APK_DIR, "manifest.json")

AUDIO_DIR = os.path.join(WS, "repo-v2.0", "audio")
BASE_URL = "https://cdn.jsdelivr.net/gh/sparkl525/-v2.0@main"
SOURCE = "VOA Learning English（美国政府作品·公共域）"


def t2cs(t):
    """'m:ss.mmm' -> 厘秒"""
    m = re.match(r"(\d+):(\d+)\.(\d+)", t)
    mm, ss, ms = int(m.group(1)), int(m.group(2)), int(m.group(3).ljust(3, "0"))
    total_ms = mm * 60000 + ss * 1000 + ms
    return int(round(total_ms / 10.0))


def fmt_lrc(cs):
    mm, rest = divmod(cs, 6000)
    ss, c = divmod(rest, 100)
    return "%02d:%02d.%02d" % (mm, ss, c)


# ─── 5 篇精品：ASR 时间戳 + 校对后文本 ───
PIECES = [
    dict(
        id="l-005",
        file="01000000-0a00-0242-9476-08dbf77b015a.mp3",
        title="PISA 2022: Students' Historic Setbacks",
        titleZh="国际学生评估 PISA：疫情造成的全球滑坡",
        topic="教育", level="medium", durationSec=381,
        lines=[
            ("0:1.540", "Students around the world suffered historic setbacks in reading and math during the COVID-19 pandemic."),
            ("0:12.040", "That information comes from results of the latest Program for International Student Assessment, or PISA."),
            ("0:22.450", "The PISA is an international comparative study of the scores of 15-year-old students on tests in reading, math and science."),
            ("0:34.510", "Nearly 700,000 students took part in the study."),
            ("0:40.860", "PISA is administered every three years."),
            ("0:44.920", "The latest results showed an unprecedented drop in performance since 2018."),
            ("0:53.310", "The PISA study was supposed to happen in 2021, but it was postponed because of the pandemic."),
            ("1:4.570", "The 2022 results were released on December 5th."),
            ("1:10.120", "It is the first extensive study with data on how the pandemic has affected student performance around the world."),
            ("1:21.450", "The study looked at 81 education systems."),
            ("1:26.410", "They include 37 member countries of the Organization for Economic Cooperation and Development, or OECD."),
            ("1:38.480", "OECD has been administering the test since 2000."),
            ("1:46.310", "The study found decreasing test scores in both rich and poor countries."),
            ("1:53.840", "Andreas Schleicher is the director for education and skills at OECD."),
            ("2:1.820", "He said the world is no longer divided between rich and well-educated countries and poor and badly educated countries."),
            ("2:13.410", "The study found the average international math score fell by 15 points since 2018."),
            ("2:22.610", "Reading scores across participating countries fell by 10 points."),
            ("2:29.110", "The OECD considers 20 points to be equal to one year of learning."),
            ("2:36.990", "Scores did not change in a major way in science."),
            ("2:43.340", "Albania saw the biggest decrease in math scores with a massive 69-point decrease."),
            ("2:51.660", "That means Albanian students lost more than three years of learning."),
            ("2:58.480", "Many countries fell by more than one year's worth of learning in math, including Germany, Iceland and the Netherlands."),
            ("3:9.460", "Four places did improve their performance between PISA 2018 and PISA 2022 in all three subjects."),
            ("3:19.730", "Brunei, Cambodia, the Dominican Republic and Chinese Taipei."),
            ("3:26.150", "Six East Asian education systems outperformed all other countries in math."),
            ("3:33.820", "Scores in Singapore were measurably higher than all other countries in all three subjects."),
            ("3:42.690", "The math scores for U.S. students were about even with the international average."),
            ("3:50.300", "Science and reading were slightly higher."),
            ("3:55.010", "The COVID-19 pandemic was a major reason for the worldwide score drops, but science and reading scores were dropping even before the pandemic."),
            ("4:7.780", "This suggests there are other reasons for the decrease."),
            ("4:14.210", "Factors such as the level of investment in education, the social value and pay levels of teachers, and educational beliefs of students have all influenced educational results."),
            ("4:30.100", "The OECD's Schleicher said."),
            ("4:34.680", "The PISA tests explore how well students can solve complex problems, think critically and communicate effectively."),
            ("4:45.970", "The goal of PISA is to provide useful information to educators and policymakers on the strengths and weaknesses of their country's education system."),
            ("4:59.860", "OECD Secretary-General Mathias Cormann released the latest PISA results in Paris."),
            ("5:9.220", "He said PISA helps to identify the comparative strengths of education systems that have performed well despite recent shocks."),
            ("5:22.680", "Policymakers can use such information to consider reforms to education systems for a brighter, more financially successful future, Cormann added."),
            ("5:36.470", "The study also reported on the fast-changing influence of technology on educational performance."),
            ("5:45.470", "The results found that moderate use of electronic devices in school is connected to higher performance."),
            ("5:55.520", "Khan Academy is a popular test prep website."),
            ("6:0.500", "It offers a free PISA quiz."),
            ("6:4.230", "You can see example questions and compare your math skills against the 15-year-old students from around the world who took the PISA 2022."),
            ("6:17.380", "I'm Gina Bennett."),
        ],
    ),
    dict(
        id="l-006",
        file="01000000-0a00-0242-5f45-08dc025bddb0.mp3",
        title="Cassini Finds Life-Ready Molecule on Enceladus",
        titleZh="土卫二冰喷流中发现生命原料分子",
        topic="天文", level="medium", durationSec=384,
        lines=[
            ("0:0.000", "NASA has discovered new evidence that Saturn's moon Enceladus holds material that is important to the early development of life."),
            ("0:12.440", "The American space agency said the new evidence came from observations made by its Cassini spacecraft."),
            ("0:22.660", "Cassini spent seven years observing seasonal changes around Saturn. During that time, the spacecraft also collected data on Saturn's moons."),
            ("0:36.380", "Two of Cassini's discoveries involved Saturn's moons Titan and Enceladus."),
            ("0:55.720", "In its observations of Titan, the spacecraft studied the moon's climate activity and recorded data on rivers, lakes and oceans."),
            ("1:4.640", "Cassini also found evidence of several Earth-like qualities of Titan that could support life."),
            ("1:16.490", "Another Cassini discovery involved the observation of hydrogen molecules in ice particles and vapor shooting upward from the moon's surface."),
            ("1:26.740", "Researchers announced that finding in the same year Cassini officially ended its mission."),
            ("1:44.120", "In announcing that discovery, NASA scientists said the presence of hydrogen molecules could signal deep-sea chemical reactions between water and rock that could create microbial life."),
            ("1:51.800", "Now the space agency is sharing a new finding that supports the earlier discoveries."),
            ("2:6.050", "Scientists examining Cassini data said they identified strong confirmation of the presence of another molecule seen as important for enabling life."),
            ("2:20.940", "Evidence of the molecule hydrogen cyanide was found in data relating to the same shooting flows of ice and water vapor observed by Cassini on Enceladus."),
            ("2:34.500", "The team used data collected by an instrument aboard Cassini designed to measure the activity of gas ions and ice particles around Saturn."),
            ("2:43.360", "The discovery process involved a lot of mathematics aiming to understand the newly examined data."),
            ("2:55.910", "The new evidence is described in a study in Nature Astronomy by scientists from NASA's Jet Propulsion Laboratory, JPL, in California."),
            ("3:4.920", "The lead writer of the study, Jonah Peter, is currently a doctoral student at Harvard University."),
            ("3:19.500", "He called the discovery of hydrogen cyanide particularly exciting, saying the molecule is the starting point for most theories on the origin of life."),
            ("3:34.320", "NASA said in a statement that for life to exist in space, there must be collections of the right building blocks. One of the most important of these is amino acids."),
            ("3:45.650", "Hydrogen cyanide is one of the most important and versatile molecules needed to form amino acids, NASA noted."),
            ("3:55.100", "The researchers said hydrogen cyanide molecules can join together in many different combinations."),
            ("4:1.860", "They can also serve as a precursor for different kinds of amino acids."),
            ("4:15.060", "In addition, the latest examination of Cassini data suggested the underground ocean on Enceladus holds a powerful source of chemical energy."),
            ("4:34.530", "The ocean sits beneath the moon's icy surface, undetected until now. The energy source is in the form of several organic compounds, some of which on Earth serve as fuel for organisms, NASA said."),
            ("4:48.820", "Astronomers involved in the research said the discovery provides fresh evidence that there may be more chemical energy inside Enceladus than previously thought."),
            ("4:57.980", "The more energy that is available, the team said, the more likely it is that life can form and survive."),
            ("5:18.950", "Not only does Enceladus seem to meet the basic requirements for habitability, said JPL's Jonah Peter, we now have an idea about how complex bio molecules could form there and what sort of chemical pathways might be involved."),
            ("5:30.520", "The search for possible life on Titan will continue when NASA launches its Dragonfly exploratory mission in 2026."),
            ("5:43.680", "Dragonfly is a drone, or pilotless aircraft. The vehicle is designed to capture images and also land on Titan to collect data."),
            ("5:50.680", "Tom Nordheim is a planetary scientist at JPL who helped lead the research."),
            ("6:6.160", "He noted that although NASA purposely sent the spacecraft crashing into Saturn's atmosphere to end its service, Cassini is still proving to be a valuable research tool."),
            ("6:21.940", "'Our study demonstrates that while Cassini's mission has ended, its observations continue to provide us with new insights about Saturn and its moons,' Nordheim said."),
            ("6:23.940", "I'm Brian Lynn."),
        ],
    ),
    dict(
        id="l-007",
        file="01000000-0aff-0242-c237-08dbfb410641.mp3",
        title="The Most Mispronounced Words of 2023",
        titleZh="2023 年最容易读错的词",
        topic="语言文化", level="easy", durationSec=247,
        lines=[
            ("0:1.330", "News reporters, politicians and public figures have had trouble saying some of the words that relate to the top stories of the year."),
            ("0:11.460", "Last week, the online language-learning company Babbel released its lists of the most mispronounced words in the United States and Britain."),
            ("0:22.580", "The two lists are a reminder of the year's news on everything from scientific discoveries to politics."),
            ("0:31.060", "Babbel teacher Malcolm Massey noted the diversity of the words."),
            ("0:36.560", "This year's words come from several different languages."),
            ("0:40.860", "'I think a lot of it is due to how close our cultures have become because of how globalized things are,' Massey said."),
            ("0:50.200", "A popular singer who spells her name SZA is on the U.S. list."),
            ("0:57.400", "Babbel notes her name is pronounced 'Siza.'"),
            ("1:2.060", "The first name of another entertainer is also on the list."),
            ("1:6.980", "Cillian Murphy is an Irish actor who starred in this summer's hit as physicist J. Robert Oppenheimer."),
            ("1:15.880", "His first name is pronounced 'KILL-ian.'"),
            ("1:20.380", "Other pronunciations on the U.S. list include the name of biotech businessman Vivek Ramaswamy."),
            ("1:28.300", "He is a Republican presidential candidate."),
            ("1:32.160", "The correct way to say his name is Vivek Ramaswamy."),
            ("1:37.630", "Two volcanoes were also on the list: Mexico's Popocatepetl and Hawaii's Kilauea."),
            ("1:46.300", "The winning word at this year's Scripps National Spelling Bee also made the U.S. list."),
            ("1:53.120", "That word was psammophile."),
            ("1:56.730", "It means any animal or plant that prefers sandy soils or areas."),
            ("2:2.620", "The coronation of King Charles in May helped to put the Stone of Scone on Britain's list."),
            ("2:9.000", "The holy stone rests beneath the coronation chair where a new king first receives his crown."),
            ("2:18.200", "Christy Denlinger teaches linguistics at the University of Texas at Austin."),
            ("2:24.410", "She said hearing an unfamiliar word often enough can help someone master it."),
            ("2:31.690", "'Anything that is kind of different from what a speaker is used to, they can learn how to pronounce things in different ways than what they're used to.'"),
            ("2:40.780", "It just takes more exposure, Denlinger said."),
            ("2:45.510", "Massey said that as one learns a new pronunciation, it is important not to stress perfection, but progress over time."),
            ("2:55.250", "So, practicing these words again and again."),
            ("2:59.500", "In some cases, the correct pronunciation is just a matter of personal choice."),
            ("3:5.960", "Earning a place on the U.S. list was the last name of Travis Kelce."),
            ("3:11.920", "The Kansas City Chiefs American football player."),
            ("3:16.270", "Whether Kelce should be pronounced with one or two syllables."),
            ("3:21.460", "Kells or Kelce has been a subject even his own family talks about."),
            ("3:28.480", "Both Travis and his brother, Philadelphia Eagles center Jason Kelce, say their last name as Kelce."),
            ("3:37.060", "On a podcast, they discussed with their father, Ed Kelce, how that pronunciation came to be."),
            ("3:44.580", "'I got tired of correcting people,' said Ed Kelce, who said that his coworkers always called him Kelce."),
            ("3:53.740", "Jason Kelce then asked, 'Should we go by Kells or Kelce?'"),
            ("3:59.800", "His father answers:"),
            ("4:1.880", "'Do whatever you want.'"),
            ("4:4.020", "'I did.'"),
            ("4:5.540", "I'm Jill Robbins."),
        ],
    ),
    dict(
        id="l-008",
        file="se-as-rappaccinis-daughter-part-1-22sep12.mp3",
        title="American Stories: Rappaccini's Daughter (Part 1)",
        titleZh="美国故事：拉帕奇尼的女儿（上）",
        topic="文学名著", level="medium", durationSec=830,
        lines=[
            ("0:0.020", "Now the Special English program American Stories. Our story today is called 'Rappaccini's Daughter.'"),
            ("0:21.470", "It was written by Nathaniel Hawthorne."),
            ("0:25.020", "We will tell the story in two parts. Here is Kay Gallant with the first part of our story."),
            ("0:47.470", "Many years ago, a young man named Giovanni Guasconti left his home in Naples to study in Northern Italy."),
            ("0:57.960", "He rented a small room on the top floor of a dark and ancient palace."),
            ("1:5.280", "Long ago, the building had belonged to a noble family."),
            ("1:10.530", "Now an old woman, Signora Lisabetta, rented its rooms to students at the University of Padua."),
            ("1:21.220", "Giovanni's room had a small window."),
            ("1:24.660", "From it he could see a large garden that had many plants and flowers."),
            ("1:32.100", "'Does the garden belong to you?' he asked Signora Lisabetta one day."),
            ("1:39.360", "'Oh no,' she said quickly. 'That garden belongs to the famous doctor Giacomo Rappaccini.'"),
            ("1:55.520", "People say he uses those plants to make strange kinds of medicine."),
            ("2:2.210", "He lives in that small brown house in the garden with his daughter Beatrice."),
            ("2:6.540", "Giovanni often sat by his window to look at the garden."),
            ("2:19.040", "He had never seen so many different kinds of plants. They all had enormous green leaves and magnificent flowers in every color of the rainbow."),
            ("2:25.040", "Giovanni's favorite plant was in a white marble vase near the house."),
            ("2:30.160", "It was covered with big purple flowers."),
            ("2:34.760", "One day while Giovanni was looking out of his window, he saw an old man in a black cape walking in the garden."),
            ("2:44.320", "The old man was tall and thin. His face was an unhealthy yellow color. His black eyes were very cold."),
            ("2:57.920", "The old man wore thick gloves on his hands and a mask over his mouth and nose."),
            ("3:5.510", "He walked carefully among the plants, as if he were walking among wild animals or poisonous snakes."),
            ("3:15.740", "Although he looked at the flowers very closely, he did not touch or smell any of them."),
            ("3:21.180", "When the old man arrived at the plant with the big purple flowers, he stopped."),
            ("3:32.200", "He took off his mask and called loudly, 'Beatrice! Come help me!'"),
            ("3:37.500", "'I am coming, father. What do you want?' answered a warm young voice from inside the house."),
            ("3:46.720", "A young woman came into the garden. Her thick, dark hair fell around her shoulders in curls. Her cheeks were pink, and her eyes were large and black."),
            ("4:0.060", "She seemed full of life, health and energy as she walked among the plants."),
            ("4:7.220", "Giovanni thought she was as beautiful as the purple flowers in the marble vase."),
            ("4:17.990", "The old man said something to her. She nodded her head as she touched and smelled the flowers that her father had been so careful to avoid."),
            ("4:28.670", "Several weeks later, Giovanni went to visit Pietro Baglioni, a friend of his father's."),
            ("4:36.120", "Professor Baglioni taught medicine at the university."),
            ("4:40.500", "During the visit, Giovanni asked about Dr. Rappaccini."),
            ("4:45.980", "'He is a great scientist,' Professor Baglioni replied, 'but he is also a dangerous man.'"),
            ("4:55.260", "'Why?' asked Giovanni."),
            ("4:58.700", "The older man shook his head slowly. 'Because Rappaccini cares more about science than he does about people.'"),
            ("5:9.780", "'He has created many terrible poisons from the plants in his garden. He thinks he can cure sickness with these poisons.'"),
            ("5:21.090", "'It is true that several times he has cured a very sick person that everyone thought would die. But Rappaccini's medicine has also killed many people.'"),
            ("5:36.350", "'I think he would sacrifice any life, even his own, for one of his experiments.'"),
            ("5:45.600", "'But what about his daughter?' Giovanni said. 'I'm sure he loves her.'"),
            ("5:53.120", "The old professor smiled at the young man."),
            ("5:56.790", "'So,' he said, 'you have heard about Beatrice Rappaccini. People say she is very beautiful. But few men in Padua have ever seen her. She never leaves her father's garden.'"),
            ("6:14.300", "Giovanni left Professor Baglioni's house as the sun was setting."),
            ("6:20.530", "On his way home, he stopped at a flower shop, where he bought some fresh flowers."),
            ("6:32.430", "He returned to his room and sat by the window. Very little sunlight was left. The garden was quiet."),
            ("6:38.240", "The purple flowers on Giovanni's favorite plant seemed to glow in the evening's fading light."),
            ("6:48.160", "Then someone came out of the doorway of the little brown house. It was Beatrice."),
            ("6:56.110", "She entered the garden and walked among the plants. She bent to touch the leaves of a plant or to smell a flower."),
            ("7:13.440", "Rappaccini's daughter seemed to grow more beautiful with each step."),
            ("7:18.880", "When she reached the purple plant, she buried her face in its flowers."),
            ("7:24.140", "Giovanni heard her say, 'Give me your breath, my sister. The ordinary air makes me weak. And give me one of your beautiful flowers.'"),
            ("7:35.780", "Beatrice gently broke off one of the largest flowers. As she lifted it to put it in her dark hair, a few drops of liquid from the flower fell to the ground."),
            ("7:46.430", "One of the drops landed on the head of a tiny lizard crawling near the feet of Beatrice."),
            ("7:55.300", "For a moment the small animal twisted violently. Then it moved no more."),
            ("8:3.500", "Beatrice did not seem surprised. She sighed and placed the flower in her hair."),
            ("8:13.550", "Giovanni leaned out of the window so he could see her better."),
            ("8:18.680", "At this moment a beautiful butterfly flew over the garden wall. It seemed to be attracted by Beatrice and flew once around her head."),
            ("8:31.160", "Then the insect's bright wings stopped, and it fell to the ground dead."),
            ("8:39.380", "Beatrice shook her head sadly."),
            ("8:44.360", "Suddenly, she looked up at Giovanni's window. She saw the young man looking at her."),
            ("8:52.450", "Giovanni picked up the flowers he had bought and threw them down to her."),
            ("8:58.800", "'Young lady,' he said, 'wear these flowers as a gift from Giovanni Guasconti.'"),
            ("9:7.880", "'Thank you,' Beatrice answered. She picked up the flowers from the ground and quickly ran to the house."),
            ("9:17.600", "She stopped at the door for a moment to wave shyly at Giovanni. It seemed to him that his flowers were beginning to turn brown in her hands."),
            ("9:32.730", "For many days, the young man stayed away from the window that looked out on Rappaccini's garden."),
            ("9:40.180", "He wished he had not talked to Beatrice, because now he felt under the power of her beauty. He was a little afraid of her too. He could not forget how the little lizard and the butterfly had died."),
            ("10:5.030", "One day, while he was returning home from his classes, he met Professor Baglioni on the street."),
            ("10:6.080", "'Well, Giovanni,' the old man said, 'have you forgotten me?' Then he looked closely at the young man. 'What is wrong, my friend? Your appearance has changed since the last time we met.'"),
            ("10:24.780", "It was true. Giovanni had become very thin. His face was white, and his eyes seemed to burn with fever."),
            ("10:36.600", "As they stood talking, a man dressed in a long black cape came down the street. He moved slowly, like a person in poor health. His face was yellow, but his eyes were sharp and black."),
            ("10:55.330", "It was the man Giovanni had seen in the garden. As he passed them, the old man nodded coldly to Professor Baglioni, but he looked at Giovanni with a great deal of interest."),
            ("11:13.360", "'It's Dr. Rappaccini,' Professor Baglioni whispered after the old man had passed them. 'Has he ever seen your face before?'"),
            ("11:32.490", "Giovanni shook his head. 'No,' he answered. 'I don't think so.'"),
            ("11:35.630", "Professor Baglioni looked worried. 'I think he has seen you before. I know that cold look of his. He looks the same way when he examines an animal he has killed in one of his experiments.'"),
            ("11:50.680", "'Giovanni, I will bet my life on it. You are the subject of one of Rappaccini's experiments.'"),
            ("12:0.420", "Giovanni stepped away from the old man. 'You are joking,' he said."),
            ("12:7.770", "'No, I am serious.' The professor took Giovanni's arm. 'Be careful, my young friend. You are in great danger.'"),
            ("12:20.780", "Giovanni pulled his arm away. 'I must be going,' he said. 'Good night.'"),
            ("12:29.820", "As Giovanni hurried to his room, he felt confused and a little frightened. Signora Lisabetta was waiting for him outside his door. She knew he was interested in Beatrice."),
            ("12:45.420", "'I have good news for you,' she said. 'I know where there is a secret entrance into Rappaccini's garden.'"),
            ("12:56.180", "Giovanni could not believe his ears. 'Where is it?' he asked. 'Show me the way.'"),
            ("13:7.700", "You have just heard part one of the story called 'Rappaccini's Daughter.'"),
            ("13:25.400", "It was written by Nathaniel Hawthorne and adapted for Special English. Your storyteller was Kay Gallant. Listen next week for the final part of our story."),
            ("13:40.300", "This is Shep O'Neill."),
        ],
    ),
    dict(
        id="l-009",
        file="01000000-c0a8-0242-4dec-08dbf1e30563_hq.mp3",
        title="South Africa to Make HIV Prevention Rings",
        titleZh="南非将量产女性防艾硅胶环",
        topic="健康", level="medium", durationSec=239,
        lines=[
            ("0:0.240", "A South African company will make vaginal devices that protect against HIV."),
            ("0:9.200", "Medical experts say that should at some point make them less costly and more easily available."),
            ("0:20.350", "HIV is a virus that causes AIDS, a serious disease that affects the body's natural defense system, or immune system."),
            ("0:32.960", "The Population Council is an international nonprofit that does medical research."),
            ("0:41.940", "It announced recently that the South African healthcare company Kiara Health will start making the devices - rings from the medical product silicone - in the next few years."),
            ("1:5.620", "The devices release a drug that helps prevent HIV infections."),
            ("1:12.420", "They are approved by nearly 12 countries and the World Health Organization, or WHO."),
            ("1:21.300", "The nonprofit group owns the rights to the ring, which are now made by a Swedish company."),
            ("1:29.410", "About 500,000 rings are currently available to women in Africa at no cost, bought by donors."),
            ("1:39.900", "Ben Phillips is a spokesperson at the UN AIDS agency."),
            ("1:45.860", "He said the ring gives women the freedom to use it without anyone else's knowledge or agreement."),
            ("1:55.720", "For women whose partners won't use a condom or allow them to take oral preventive HIV medicines, this gives them another option, he said."),
            ("2:10.880", "A condom is a device worn by a male partner to prevent pregnancy and the spread of sexual diseases."),
            ("2:21.130", "HIV remains the leading cause of death among women of reproductive age in Africa. And 60 percent of new infections are in women."),
            ("2:39.900", "Information from WHO shows the ring releases the drug dapivirine in small amounts over a month."),
            ("2:47.480", "It currently costs 12 to 16 dollars."),
            ("2:52.500", "But experts expect the price to drop once it is widely produced in Africa."),
            ("3:0.300", "Developers are also working on a version that will last up to three months, which should also lower the yearly cost."),
            ("3:11.440", "WHO has suggested the ring be used as an additional tool for women at high risk of HIV."),
            ("3:21.560", "Officials in more than 12 African countries, including South Africa, Botswana, Malawi, Uganda and Zimbabwe, have also approved it."),
            ("3:35.970", "WHO pointed to two studies in its approval, saying the ring reduced women's chances of getting HIV by about a third."),
            ("3:48.690", "While other research has suggested the risk could be dropped by more than 50 percent."),
            ("3:56.620", "I'm Gregory Stockel."),
        ],
    ),
]


def main():
    os.makedirs(ART_DIR, exist_ok=True)
    os.makedirs(APK_DIR, exist_ok=True)

    new_items = []
    for p in PIECES:
        # 写 LRC
        out = ["[ti:%s]" % p["title"], "[by:VOA Learning English]", ""]
        for t, text in p["lines"]:
            out.append("[%s]%s" % (fmt_lrc(t2cs(t)), text))
        lrc = "\n".join(out) + "\n"
        lrc_path = os.path.join(ART_DIR, p["id"] + ".lrc")
        open(lrc_path, "w", encoding="utf-8").write(lrc)

        words = sum(len(x.split()) for _, x in p["lines"])
        audio_path = os.path.join(AUDIO_DIR, p["id"] + ".mp3")
        size = os.path.getsize(audio_path) if os.path.exists(audio_path) else 0
        new_items.append(dict(
            id=p["id"], title=p["title"], titleZh=p["titleZh"],
            topic=p["topic"], level=p["level"], words=words,
            sentences=len(p["lines"]), durationSec=p["durationSec"],
            builtin=False, hasAudio=True, audioBytes=size,
            text="articles/%s.lrc" % p["id"], audio="audio/%s.mp3" % p["id"],
            source=SOURCE,
        ))
        print("%s  %-24s %4d 词 %2d 句 %5.2f MB  %s" % (
            p["id"], p["titleZh"], words, len(p["lines"]), size / 1048576.0,
            fmt_lrc(t2cs(p["lines"][-1][0]))))

    # 合并进现有清单
    manifest = json.load(open(MANIFEST_REPO, encoding="utf-8"))
    have = {it["id"] for it in manifest["items"]}
    added = 0
    for it in new_items:
        if it["id"] not in have:
            manifest["items"].append(it)
            added += 1
    manifest["version"] = 2
    manifest["updated"] = "2026-10-20"
    manifest["note"] = ("词桨 App 听力素材清单。builtin=true 的已内置在 APK，"
                        "其余由「素材包」开关联网下载到手机。VOA 为美国政府作品（公共域）。")

    open(MANIFEST_REPO, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    # APK 侧只更新 manifest.json（新条目都不是 builtin，不拷音频）
    open(MANIFEST_APK, "w", encoding="utf-8").write(
        json.dumps(manifest, ensure_ascii=False, indent=2))
    print("\n新增 %d 条；清单共 %d 条，版本 %d（%s）" % (
        added, len(manifest["items"]), manifest["version"], manifest["updated"]))


if __name__ == "__main__":
    main()
